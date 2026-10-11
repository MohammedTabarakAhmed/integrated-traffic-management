package gov.itms.rental.api;

import static gov.itms.rental.api.ContractClient.regional;
import static gov.itms.rental.api.ContractClient.user;
import static org.assertj.core.api.Assertions.assertThat;

import gov.itms.platform.security.AuthorisationResolver;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Rental companies, branches, contracts and condition inspections (TR-020..TR-034); contract and scope checked. */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("TR-020")
class RentalApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @TestConfiguration
    static class Scopes {
        @Bean
        AuthorisationResolver authorisationResolver() {
            return new ContractClient.RegionalResolver();
        }
    }

    private static final RequestPostProcessor REGULATOR = user("u-reg", "RENTAL_REGULATOR", "rental.company.manage",
            "rental.contract.record", "rental.contract.read");
    private static final RequestPostProcessor SOUTH_ONLY = regional("u-south", "RENTAL_REGULATOR", List.of("SOUTH"),
            "rental.company.manage", "rental.contract.record", "rental.contract.read");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    private ContractClient api;

    @BeforeEach
    void client() {
        api = new ContractClient(mvc, json, "/openapi/rental-service.v1.yaml");
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String company() throws Exception {
        String file = id("TF-RC");
        api.expect(api.post("/v1/rental-companies", Map.of("trafficFileNumber", file, "commercialLicence",
                id("CL-"), "region", "NORTH"), REGULATOR), 201);
        api.expect(api.post("/v1/rental-companies/" + file + "/branches", Map.of("code", "AIRPORT", "name",
                "Airport desk", "region", "NORTH"), REGULATOR), 201);
        return file;
    }

    private Map<String, Object> contract(String company) {
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> m = new HashMap<>(Map.of("companyFile", company, "vehiclePlate", "A 5555", "startsAt",
                start.toString(), "endsAt", start.plus(3, ChronoUnit.DAYS).toString(), "pickupBranch", "AIRPORT",
                "returnBranch", "AIRPORT", "type", "DAILY", "bondMinor", 50_000));
        m.put("renterFile", "TF-RENTER");
        return m;
    }

    @Test
    @Tag("TR-021")
    @Tag("TR-030")
    @Tag("TR-033")
    @DisplayName("TR-020/021/030/033: company with branches, contracts for residents or visitors, suspension blocks "
            + "contracts until reinstated")
    void companiesAndContracts() throws Exception {
        String company = company();
        assertThat(api.post("/v1/rental-companies/" + company + "/branches", Map.of("code", "CITY", "name", "City",
                "region", "SOUTH"), REGULATOR).getResponse().getStatus()).as("other region").isEqualTo(422);
        JsonNode resident = api.expect(api.post("/v1/rental-contracts", contract(company), REGULATOR), 201);
        assertThat(resident.get("status").asString()).isEqualTo("ACTIVE");
        Map<String, Object> visitor = contract(company);
        visitor.remove("renterFile");
        visitor.put("visitorPassport", "P1234567");
        visitor.put("foreignLicence", "FL-99");
        visitor.put("additionalDrivers", List.of("TF-SPOUSE"));
        api.expect(api.post("/v1/rental-contracts", visitor, REGULATOR), 201);
        Map<String, Object> both = contract(company);
        both.put("visitorPassport", "P1");
        both.put("foreignLicence", "FL-1");
        assertThat(api.post("/v1/rental-contracts", both, REGULATOR).getResponse().getStatus()).isEqualTo(422);
        Map<String, Object> badBranch = contract(company);
        badBranch.put("returnBranch", "NOWHERE");
        assertThat(api.post("/v1/rental-contracts", badBranch, REGULATOR).getResponse().getStatus()).isEqualTo(422);
        assertThat(api.expect(api.get("/v1/rental-contracts/" + resident.get("id").asString(), REGULATOR), 200)
                .get("vehiclePlate").asString()).isEqualTo("A 5555");

        assertThat(api.post("/v1/rental-companies/" + company + "/suspensions", Map.of("reason", " "), REGULATOR)
                .getResponse().getStatus()).isIn(400, 422);
        assertThat(api.expect(api.post("/v1/rental-companies/" + company + "/suspensions", Map.of("reason",
                "licence lapsed"), REGULATOR), 200).get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(api.post("/v1/rental-companies/" + company + "/suspensions", Map.of("reason", "again"),
                REGULATOR).getResponse().getStatus()).isEqualTo(409);
        assertThat(api.post("/v1/rental-contracts", contract(company), REGULATOR).getResponse().getStatus())
                .isEqualTo(409);
        api.expect(api.post("/v1/rental-companies/" + company + "/reinstatements", Map.of("reason", "licence renewed"),
                REGULATOR), 200);
        api.expect(api.post("/v1/rental-contracts", contract(company), REGULATOR), 201);
        assertThat(api.post("/v1/rental-companies", Map.of("trafficFileNumber", company, "commercialLicence",
                id("CL-"), "region", "NORTH"), REGULATOR).getResponse().getStatus()).isEqualTo(409);
        assertThat(api.get("/v1/rental-contracts/" + UUID.randomUUID(), REGULATOR).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @Tag("TR-034")
    @DisplayName("TR-034: pickup then return inspections with odometer, fuel and damage; one of each")
    void inspections() throws Exception {
        String contract = api.expect(api.post("/v1/rental-contracts", contract(company()), REGULATOR), 201).get("id")
                .asString();
        String path = "/v1/rental-contracts/" + contract + "/inspections";
        Map<String, Object> ret = new HashMap<>(Map.of("type", "RETURN", "inspectedAt", Instant.now().plusSeconds(60)
                .toString(), "odometerKm", 12_400, "fuelPercent", 50, "damageNotes", List.of("scratch rear bumper"),
                "evidenceIds", List.of("DOC-1")));
        assertThat(api.post(path, ret, REGULATOR).getResponse().getStatus()).as("pickup first").isEqualTo(409);
        Map<String, Object> pickup = new HashMap<>(Map.of("type", "PICKUP", "inspectedAt", Instant.now().plusSeconds(5)
                .toString(), "odometerKm", 12_000, "fuelPercent", 100, "damageNotes", List.of(), "evidenceIds",
                List.of()));
        api.expect(api.post(path, pickup, REGULATOR), 201);
        assertThat(api.post(path, pickup, REGULATOR).getResponse().getStatus()).isEqualTo(409);
        ret.put("odometerKm", 11_000);
        assertThat(api.post(path, ret, REGULATOR).getResponse().getStatus()).isEqualTo(422);
        ret.put("odometerKm", 12_400);
        api.expect(api.post(path, ret, REGULATOR), 201);
        assertThat(api.expect(api.get(path, REGULATOR), 200)).hasSize(2);
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.rental.inspection-recorded'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: a regulator scoped to another region cannot touch a NORTH company or its contracts")
    void regionalScope() throws Exception {
        String company = company();
        String contract = api.expect(api.post("/v1/rental-contracts", contract(company), REGULATOR), 201).get("id")
                .asString();
        assertThat(api.post("/v1/rental-contracts", contract(company), SOUTH_ONLY).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(api.get("/v1/rental-contracts/" + contract, SOUTH_ONLY).getResponse().getStatus()).isEqualTo(403);
        assertThat(api.post("/v1/rental-companies", Map.of("trafficFileNumber", id("TF-"), "commercialLicence",
                id("CL-"), "region", "NORTH"), SOUTH_ONLY).getResponse().getStatus()).isEqualTo(403);
        assertThat(api.post("/v1/rental-companies/" + company + "/suspensions", Map.of("reason", "x y z"),
                SOUTH_ONLY).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @Tag("TR-032")
    @Tag("VF-014")
    @DisplayName("TR-032, UAT-2: the contract covering a plate at an instant names the renter; outside it, none")
    void coverage() throws Exception {
        RequestPostProcessor violations = user("svc-violation", "RENTAL_COVERAGE_READER", "rental.contract.coverage");
        String company = company();
        String plate = "U" + (1000 + new java.util.Random().nextInt(9000));
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> resident = contract(company);
        resident.put("vehiclePlate", plate);
        resident.put("startsAt", start.toString());
        resident.put("endsAt", start.plus(2, ChronoUnit.DAYS).toString());
        String contractId = api.expect(api.post("/v1/rental-contracts", resident, REGULATOR), 201).get("id")
                .asString();

        // UAT-2: contract day 1 10:00 to day 3 10:00, violation day 2 18:00
        JsonNode during = api.expect(api.get("/v1/rental-contracts/coverage?plate=" + plate
                + "&at=" + start.plus(32, ChronoUnit.HOURS), violations), 200);
        assertThat(during.get("covered").asBoolean()).isTrue();
        assertThat(during.get("contractId").asString()).isEqualTo(contractId);
        assertThat(during.get("renterFile").asString()).isEqualTo("TF-RENTER");
        assertThat(during.get("companyFile").asString()).isEqualTo(company);
        assertThat(during.get("visitor").asBoolean()).isFalse();
        for (Instant outside : List.of(start.minusSeconds(1), start.plus(2, ChronoUnit.DAYS))) {
            assertThat(api.expect(api.get("/v1/rental-contracts/coverage?plate=" + plate
                    + "&at=" + outside, violations), 200).get("covered").asBoolean()).as(outside.toString()).isFalse();
        }
        assertThat(db.sql("select count(*) from audit_outbox where action = 'RENTAL_COVERAGE_CHECKED' "
                + "and entity_id = :id").param("id", contractId).query(Long.class).single()).isEqualTo(1);

        Map<String, Object> visitor = contract(company);
        String visitorPlate = plate + "V";
        visitor.put("vehiclePlate", visitorPlate);
        visitor.remove("renterFile");
        visitor.put("visitorPassport", "P7654321");
        visitor.put("foreignLicence", "FL-7");
        api.expect(api.post("/v1/rental-contracts", visitor, REGULATOR), 201);
        JsonNode visiting = api.expect(api.get("/v1/rental-contracts/coverage?plate=" + visitorPlate
                + "&at=" + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS), violations), 200);
        assertThat(visiting.get("covered").asBoolean()).isTrue();
        assertThat(visiting.get("visitor").asBoolean()).isTrue();
        assertThat(visiting.get("renterFile").isNull()).isTrue();

        assertThat(api.get("/v1/rental-contracts/coverage?plate=X&at=" + start, REGULATOR).getResponse().getStatus())
                .as("needs rental.contract.coverage").isEqualTo(403);
    }
}

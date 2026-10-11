package gov.itms.roadpermit.api;

import static gov.itms.roadpermit.api.ContractClient.user;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Parking and road-use permits through every affected authority to issue (RE-010..RE-023); contract-checked. */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("RE-020")
class RoadPermitApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final RequestPostProcessor APPLICANT = user("c-hauler", "HAULIER", "road-permit.apply");
    private static final RequestPostProcessor AUTHORITY = user("u-roads", "ROAD_AUTHORITY", "road-permit.approve");
    private static final RequestPostProcessor MANAGER = user("u-permits", "PERMIT_OFFICER", "road-permit.manage");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    private ContractClient api;

    @BeforeEach
    void client() {
        api = new ContractClient(mvc, json, "/openapi/road-permit-service.v1.yaml");
    }

    private static Map<String, Object> oversize(List<String> authorities) {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> m = new HashMap<>(Map.of("type", "OVERSIZE", "applicant", "TF-HAUL", "vehicles",
                List.of("A 1234"), "routeSegments", List.of("SEG-1", "SEG-2"), "region", "NORTH", "startsAt",
                start.toString(), "endsAt", start.plus(2, ChronoUnit.DAYS).toString(), "vehicleLimit", 2,
                "requiredAuthorities", authorities));
        m.put("widthMetres", 3.4);
        m.put("loadTonnes", 48.0);
        return m;
    }

    @Test
    @Tag("RE-021")
    @Tag("RE-022")
    @DisplayName("RE-020..022: approved only when every affected authority has approved; then issued and cancelled")
    void oversizeLifecycle() throws Exception {
        JsonNode permit = api.expect(api.post("/v1/road-permits", oversize(List.of("ROADS", "POLICE")), APPLICANT),
                201);
        assertThat(permit.get("status").asString()).isEqualTo("APPLIED");
        assertThat(permit.get("number").asString()).startsWith("RPU-");
        String base = "/v1/road-permits/" + permit.get("id").asString();
        assertThat(api.post(base + "/transitions", Map.of("action", "issue"), MANAGER).getResponse().getStatus())
                .as("not approved yet").isEqualTo(400);
        assertThat(api.expect(api.post(base + "/approvals", Map.of("authority", "ROADS", "reason", "route ok"),
                AUTHORITY), 200).get("status").asString()).isEqualTo("APPLIED");
        assertThat(api.expect(api.post(base + "/approvals", Map.of("authority", "POLICE"), AUTHORITY), 200)
                .get("status").asString()).isEqualTo("APPROVED");
        assertThat(api.post(base + "/approvals", Map.of("authority", "POLICE"), AUTHORITY).getResponse()
                .getStatus()).isEqualTo(400);
        assertThat(api.expect(api.post(base + "/transitions", Map.of("action", "issue"), MANAGER), 200)
                .get("status").asString()).isEqualTo("ISSUED");
        assertThat(api.post(base + "/transitions", Map.of("action", "suspend"), MANAGER).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(api.expect(api.post(base + "/transitions", Map.of("action", "cancel", "reason", "event moved"),
                MANAGER), 200).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(db.sql("select count(*) from event_outbox where subject = :id").param("id",
                permit.get("id").asString()).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    @Tag("RE-010")
    @Tag("RE-011")
    @Tag("CC-001")
    @DisplayName("RE-010/011: a parking permit needs an eligibility reference; invalid requests, unknown permits and "
            + "missing permissions are refused")
    void parkingAndRefusals() throws Exception {
        Map<String, Object> parking = oversize(List.of("MUNICIPALITY"));
        parking.put("type", "PARKING");
        parking.remove("routeSegments");
        assertThat(api.post("/v1/road-permits", parking, APPLICANT).getResponse().getStatus()).isEqualTo(400);
        parking.put("eligibilityReference", "RES-77");
        api.expect(api.post("/v1/road-permits", parking, APPLICANT), 201);
        Map<String, Object> noRoute = oversize(List.of("ROADS"));
        noRoute.put("routeSegments", List.of());
        assertThat(api.post("/v1/road-permits", noRoute, APPLICANT).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> tooMany = oversize(List.of("ROADS"));
        tooMany.put("vehicles", List.of("A 1", "A 2", "A 3"));
        assertThat(api.post("/v1/road-permits", tooMany, APPLICANT).getResponse().getStatus()).isEqualTo(400);
        assertThat(api.post("/v1/road-permits/" + UUID.randomUUID() + "/approvals", Map.of("authority", "ROADS"),
                AUTHORITY).getResponse().getStatus()).isEqualTo(404);
        assertThat(api.post("/v1/road-permits", oversize(List.of("ROADS")), AUTHORITY).getResponse().getStatus())
                .isEqualTo(403);
    }
}

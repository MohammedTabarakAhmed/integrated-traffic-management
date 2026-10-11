package gov.itms.accident.api;

import static gov.itms.accident.api.ContractClient.regional;
import static gov.itms.accident.api.ContractClient.user;
import static org.assertj.core.api.Assertions.assertThat;

import gov.itms.platform.security.AuthorisationResolver;
import java.time.Instant;
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

/**
 * Accident reports, fault decisions, objections, repair permits, paper books, hit-and-run identification, closure
 * and partner hand-offs (AC-001..AC-022); contract-checked. The objection window is shortened to 3 seconds.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.accidents.objection-window=PT3S"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("AC-001")
class AccidentApiIT {

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

    private static final RequestPostProcessor OFFICER = user("u-officer", "ACCIDENT_OFFICER", "accident.report",
            "accident.read", "accident.investigate", "accident.repair-permit.issue", "accident.paper-book.issue",
            "accident.paper-book.record", "accident.paper-book.reconcile", "accident.prosecution.refer",
            "accident.external.send");
    private static final RequestPostProcessor PARTY = user("c-party", "CUSTOMER", "accident.objection.submit");
    private static final RequestPostProcessor SENIOR = user("u-senior", "SENIOR_INVESTIGATOR",
            "accident.objection.review");
    private static final RequestPostProcessor GARAGE = user("u-garage", "GARAGE", "accident.repair-permit.verify",
            "accident.repair-permit.cancel");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    private ContractClient api;

    @BeforeEach
    void client() {
        api = new ContractClient(mvc, json, "/openapi/accident-service.v1.yaml");
    }

    private static Map<String, Object> party(String file, String plate, Integer fault, boolean agrees) {
        Map<String, Object> m = new HashMap<>(Map.of("role", "DRIVER", "agrees", agrees, "unknownParty", false));
        m.put("trafficFileNumber", file);
        m.put("vehiclePlate", plate);
        if (fault != null) {
            m.put("faultPercent", fault);
        }
        return m;
    }

    private static Map<String, Object> report(List<Map<String, Object>> parties) {
        return new HashMap<>(Map.of("region", "NORTH", "occurredAt", Instant.now().minusSeconds(600).toString(),
                "location", "E11 km 12", "origin", "OFFICER_DEVICE", "parties", parties, "evidenceDocumentIds",
                List.of("DOC-PHOTO-1")));
    }

    @Test
    @Tag("AC-006")
    @DisplayName("AC-006: each person's role, seat, injury degree, transport of the injured and intoxication are kept")
    void personDetails() throws Exception {
        Map<String, Object> driver = party("TF-P1", "P 1", null, false);
        driver.putAll(Map.of("seatPosition", "FRONT_LEFT", "injuryDegree", "MINOR", "intoxicationStatus",
                "NEGATIVE"));
        Map<String, Object> passenger = new HashMap<>(Map.of("role", "PASSENGER", "agrees", false, "unknownParty",
                false, "trafficFileNumber", "TF-P2", "vehiclePlate", "P 1", "seatPosition", "REAR_RIGHT",
                "injuryDegree", "SEVERE", "transportMethod", "AMBULANCE", "intoxicationStatus", "NOT_TESTED"));
        Map<String, Object> pedestrian = new HashMap<>(Map.of("role", "PEDESTRIAN", "agrees", false, "unknownParty",
                false, "trafficFileNumber", "TF-P3", "injuryDegree", "MODERATE", "transportMethod", "PRIVATE_CAR"));
        String id = api.expect(api.post("/v1/accidents", report(List.of(driver, passenger, pedestrian)), OFFICER),
                201).get("id").asString();

        JsonNode parties = api.expect(api.get("/v1/accidents/" + id, OFFICER), 200).get("parties");
        assertThat(parties).hasSize(3);
        assertThat(parties.get(0).get("role").asString()).isEqualTo("DRIVER");
        assertThat(parties.get(0).get("seatPosition").asString()).isEqualTo("FRONT_LEFT");
        assertThat(parties.get(0).get("intoxicationStatus").asString()).isEqualTo("NEGATIVE");
        assertThat(parties.get(1).get("role").asString()).isEqualTo("PASSENGER");
        assertThat(parties.get(1).get("seatPosition").asString()).isEqualTo("REAR_RIGHT");
        assertThat(parties.get(1).get("injuryDegree").asString()).isEqualTo("SEVERE");
        assertThat(parties.get(1).get("transportMethod").asString()).isEqualTo("AMBULANCE");
        assertThat(parties.get(2).get("role").asString()).isEqualTo("PEDESTRIAN");
        assertThat(parties.get(2).get("injuryDegree").asString()).isEqualTo("MODERATE");
        assertThat(parties.get(2).get("transportMethod").asString()).isEqualTo("PRIVATE_CAR");
    }

    @Test
    @Tag("AC-002")
    @Tag("AC-005")
    @Tag("AC-009")
    @DisplayName("AC-002/005/009: agreed shares decide fault at once; disputed ones are decided by an investigator; an "
            + "objection inside the window reopens it; closure only after the window")
    void faultObjectionClosure() throws Exception {
        JsonNode agreed = api.expect(api.post("/v1/accidents", report(List.of(party("TF-A1", "A 1", 70, true),
                party("TF-A2", "A 2", 30, true))), OFFICER), 201);
        assertThat(agreed.get("status").asString()).isEqualTo("FAULT_DECIDED");
        assertThat(api.post("/v1/accidents", report(List.of(party("TF-A1", "A 1", 70, true),
                party("TF-A2", "A 2", 20, true))), OFFICER).getResponse().getStatus()).isEqualTo(422);

        JsonNode disputed = api.expect(api.post("/v1/accidents", report(List.of(party("TF-B1", "B 1", null, false),
                party("TF-B2", "B 2", null, false))), OFFICER), 201);
        assertThat(disputed.get("status").asString()).isEqualTo("UNDER_INVESTIGATION");
        String id = disputed.get("id").asString();
        assertThat(api.post("/v1/accidents/" + id + "/fault-decisions", Map.of("reason", "camera footage", "shares",
                List.of(Map.of("partyNo", 1, "faultPercent", 100))), OFFICER).getResponse().getStatus()).isEqualTo(422);
        JsonNode decided = api.expect(api.post("/v1/accidents/" + id + "/fault-decisions", Map.of("reason",
                "camera footage", "shares", List.of(Map.of("partyNo", 1, "faultPercent", 100), Map.of("partyNo", 2,
                        "faultPercent", 0))), OFFICER), 200);
        assertThat(decided.get("status").asString()).isEqualTo("FAULT_DECIDED");
        assertThat(api.post("/v1/accidents/" + id + "/fault-decisions", Map.of("reason", "again", "shares",
                List.of(Map.of("partyNo", 1, "faultPercent", 50), Map.of("partyNo", 2, "faultPercent", 50))), OFFICER)
                .getResponse().getStatus()).isEqualTo(409);

        assertThat(api.post("/v1/accidents/" + id + "/closures", Map.of("reason", "too early"), OFFICER)
                .getResponse().getStatus()).as("inside the objection window").isEqualTo(422);
        JsonNode objection = api.expect(api.post("/v1/accidents/" + id + "/objections", Map.of("partyNo", 1,
                "reason", "the other car ran the light"), PARTY), 201);
        assertThat(objection.get("status").asString()).isEqualTo("PENDING");
        JsonNode accepted = api.expect(api.post("/v1/accident-objections/" + objection.get("id").asString()
                + "/reviews", Map.of("accept", true, "reason", "new witness"), SENIOR), 200);
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(api.expect(api.get("/v1/accidents/" + id, OFFICER), 200).get("status").asString())
                .isEqualTo("UNDER_INVESTIGATION");
        assertThat(api.post("/v1/accident-objections/" + objection.get("id").asString() + "/reviews",
                Map.of("accept", false, "reason", "again"), SENIOR).getResponse().getStatus()).isEqualTo(422);

        String agreedId = agreed.get("id").asString();
        assertThat(api.post("/v1/accidents/" + agreedId + "/objections", Map.of("partyNo", 9, "reason", "who?"),
                PARTY).getResponse().getStatus()).isEqualTo(422);
        Thread.sleep(3_500);
        assertThat(api.post("/v1/accidents/" + agreedId + "/objections", Map.of("partyNo", 1, "reason", "late"),
                PARTY).getResponse().getStatus()).isEqualTo(422);
        assertThat(api.expect(api.post("/v1/accidents/" + agreedId + "/closures", Map.of("reason",
                "objection period elapsed"), OFFICER), 200).get("status").asString()).isEqualTo("CLOSED");
    }

    @Test
    @Tag("AC-020")
    @Tag("AC-021")
    @DisplayName("AC-020/021: a repair permit for a listed vehicle after fault; verified by its code; cancelled once")
    void repairPermits() throws Exception {
        JsonNode open = api.expect(api.post("/v1/accidents", report(List.of(party("TF-R1", "R 1", null, false))),
                OFFICER), 201);
        assertThat(api.post("/v1/accidents/" + open.get("id").asString() + "/repair-permits", Map.of("vehiclePlate",
                "R 1", "damagedParts", List.of("bumper")), OFFICER).getResponse().getStatus()).isEqualTo(409);
        String id = api.expect(api.post("/v1/accidents", report(List.of(party("TF-R2", "R 2", 100, true))), OFFICER),
                201).get("id").asString();
        assertThat(api.post("/v1/accidents/" + id + "/repair-permits", Map.of("vehiclePlate", "Z 9", "damagedParts",
                List.of("bumper")), OFFICER).getResponse().getStatus()).isEqualTo(422);
        JsonNode permit = api.expect(api.post("/v1/accidents/" + id + "/repair-permits", Map.of("vehiclePlate", "R 2",
                "damagedParts", List.of("front bumper", "left headlight")), OFFICER), 201);
        String number = permit.get("number").asString();
        String code = permit.get("verificationCode").asString();
        assertThat(api.expect(api.get("/v1/repair-permits/" + number + "?code=" + code, GARAGE), 200).get("valid")
                .asBoolean()).isTrue();
        assertThat(api.expect(api.get("/v1/repair-permits/" + number + "?code=WRONG", GARAGE), 200).get("valid")
                .asBoolean()).isFalse();
        assertThat(api.expect(api.post("/v1/repair-permits/" + number + "/cancellations", Map.of("reason",
                "issued in error"), GARAGE), 200).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(api.post("/v1/repair-permits/" + number + "/cancellations", Map.of("reason", "again"), GARAGE)
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(api.expect(api.get("/v1/repair-permits/" + number + "?code=" + code, GARAGE), 200).get("valid")
                .asBoolean()).isFalse();
        assertThat(api.get("/v1/repair-permits/RP-NONE?code=x", GARAGE).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @Tag("AC-008")
    @Tag("AC-011")
    @Tag("AC-022")
    @DisplayName("AC-008/011/022: paper books, hit-and-run identification, prosecution referral and partner hand-offs")
    void paperBooksHitRunAndReferrals() throws Exception {
        Map<String, Object> unknown = new HashMap<>(Map.of("role", "DRIVER", "agrees", false, "unknownParty", true));
        JsonNode accident = api.expect(api.post("/v1/accidents", report(List.of(party("TF-H1", "H 1", null, false),
                unknown)), OFFICER), 201);
        String id = accident.get("id").asString();
        assertThat(api.post("/v1/accidents/" + id + "/hit-and-run-parties/1/identifications", Map.of(
                "vehiclePlate", "X 1"), OFFICER).getResponse().getStatus()).as("party 1 is known").isEqualTo(422);
        JsonNode found = api.expect(api.post("/v1/accidents/" + id + "/hit-and-run-parties/2/identifications",
                Map.of("vehiclePlate", "HR 77"), OFFICER), 200);
        assertThat(found.get("parties").get(1).get("vehiclePlate").asString()).isEqualTo("HR 77");

        assertThat(api.post("/v1/accidents/" + id + "/ambulance-requests", null, OFFICER).getResponse().getStatus())
                .isEqualTo(202);
        assertThat(api.post("/v1/accidents/" + id + "/insurer-notifications", null, OFFICER).getResponse()
                .getStatus()).isEqualTo(202);
        assertThat(api.post("/v1/accidents/" + id + "/prosecution-referrals", Map.of("reason", " "), OFFICER)
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(api.expect(api.post("/v1/accidents/" + id + "/prosecution-referrals", Map.of("reason",
                "fatal hit and run"), OFFICER), 200).get("status").asString()).isEqualTo("REFERRED_TO_PROSECUTION");
        assertThat(db.sql("select count(*) from accident_external_exchange where accident_id = cast(:id as uuid)")
                .param("id", id).query(Long.class).single()).isEqualTo(3);

        String serial = "AB-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        api.expect(api.post("/v1/accident-paper-books", Map.of("serial", serial, "region", "NORTH", "officerId",
                "u-officer"), OFFICER), 201);
        api.expect(api.post("/v1/accident-paper-books/" + serial + "/reports", Map.of("reportSerial", "PR-1",
                "accidentId", id), OFFICER), 200);
        assertThat(api.post("/v1/accident-paper-books/" + serial + "/reconciliation", Map.of("reportSerials",
                List.of("PR-1", "PR-2")), OFFICER).getResponse().getStatus()).isEqualTo(422);
        assertThat(api.expect(api.post("/v1/accident-paper-books/" + serial + "/reconciliation", Map.of(
                "reportSerials", List.of("PR-1")), OFFICER), 200).get("status").asString()).isEqualTo("RECONCILED");
        String lost = "AB-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        api.expect(api.post("/v1/accident-paper-books", Map.of("serial", lost, "region", "NORTH", "officerId",
                "u-officer"), OFFICER), 201);
        assertThat(api.expect(api.post("/v1/accident-paper-books/" + lost + "/losses", Map.of("reason",
                "left at the scene"), OFFICER), 200).get("status").asString()).isEqualTo("LOST");
        assertThat(api.post("/v1/accident-paper-books/NOPE-1/losses", Map.of("reason", "x"), OFFICER).getResponse()
                .getStatus()).isEqualTo(404);
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: callers outside the accident's region are refused; unknown accidents are 404")
    void scopeAndNotFound() throws Exception {
        String id = api.expect(api.post("/v1/accidents", report(List.of(party("TF-S1", "S 1", 100, true))), OFFICER),
                201).get("id").asString();
        RequestPostProcessor south = regional("u-south", "ACCIDENT_OFFICER", List.of("SOUTH"), "accident.report",
                "accident.read", "accident.investigate");
        assertThat(api.get("/v1/accidents/" + id, south).getResponse().getStatus()).isEqualTo(403);
        assertThat(api.post("/v1/accidents", report(List.of(party("TF-S2", "S 2", 100, true))), south).getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(api.get("/v1/accidents/" + UUID.randomUUID(), OFFICER).getResponse().getStatus()).isEqualTo(404);
        assertThat(api.post("/v1/accidents", report(List.of(Map.of("role", "DRIVER", "agrees", false,
                "unknownParty", false))), OFFICER).getResponse().getStatus()).isEqualTo(422);
    }
}

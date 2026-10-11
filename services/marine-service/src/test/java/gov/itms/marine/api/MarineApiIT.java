package gov.itms.marine.api;

import static gov.itms.marine.api.ContractClient.user;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Marine craft, inspections, renewals, transfers and operator licences; contract-checked (RE-030..RE-033). */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("RE-030")
class MarineApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final RequestPostProcessor OFFICER = user("u-marine", "MARINE_OFFICER", "marine.craft.manage",
            "marine.operator-licence.issue");
    private static final RequestPostProcessor WORKSHOP = user("u-workshop", "MARINE_WORKSHOP",
            "marine.inspection.record");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    private ContractClient api;

    @BeforeEach
    void client() {
        api = new ContractClient(mvc, json, "/openapi/marine-service.v1.yaml");
    }

    private String craft() throws Exception {
        String hull = "HULL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        JsonNode c = api.expect(api.post("/v1/marine-craft", Map.of("hullId", hull, "type", "MOTOR_BOAT",
                "lengthMetres", 8.5, "engines", 2, "capacity", 6, "homePort", "NORTH_PORT", "owner", "TF-1"),
                OFFICER), 201);
        assertThat(c.get("status").asString()).isEqualTo("ACTIVE");
        return hull;
    }

    @Test
    @Tag("RE-031")
    @DisplayName("RE-030/031: register, inspect, renew only after a passed renewal inspection, transfer ownership")
    void craftLifecycle() throws Exception {
        String hull = craft();
        String base = "/v1/marine-craft/" + hull;
        assertThat(api.post(base + "/renewals", Map.of("validityDays", 365), OFFICER).getResponse().getStatus())
                .as("no renewal inspection yet").isEqualTo(400);
        api.expect(api.post(base + "/inspections", Map.of("purpose", "RENEWAL", "result", "FAIL", "workshop",
                "WS_1"), WORKSHOP), 200);
        assertThat(api.post(base + "/renewals", Map.of("validityDays", 365), OFFICER).getResponse().getStatus())
                .as("last renewal inspection failed").isEqualTo(400);
        api.expect(api.post(base + "/inspections", Map.of("purpose", "RENEWAL", "result", "PASS", "workshop", "WS_1",
                "documentId", "DOC-1"), WORKSHOP), 200);
        api.expect(api.post(base + "/renewals", Map.of("validityDays", 365), OFFICER), 200);
        JsonNode moved = api.expect(api.post(base + "/ownership-transfers", Map.of("owner", "TF-2"), OFFICER), 200);
        assertThat(moved.get("owner").asString()).isEqualTo("TF-2");
        assertThat(api.post("/v1/marine-craft/NOPE/inspections", Map.of("purpose", "SAFETY", "result", "PASS",
                "workshop", "WS_1"), WORKSHOP).getResponse().getStatus()).isEqualTo(404);
        assertThat(db.sql("select count(*) from audit_outbox where action like 'MARINE_%'").query(Long.class)
                .single()).isGreaterThanOrEqualTo(5);
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.marine.ownership-transferred'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Tag("RE-032")
    @Tag("CC-001")
    @DisplayName("RE-032: an operator licence is issued with an expiry; permissions and idempotency are enforced")
    void operatorLicenceAndGuards() throws Exception {
        JsonNode licence = api.expect(api.post("/v1/marine-operator-licences", Map.of("trafficFileNumber", "TF-9",
                "craftType", "MOTOR_BOAT", "validityDays", 730), OFFICER), 201);
        assertThat(licence.get("number").asString()).startsWith("MOL-");
        assertThat(api.post("/v1/marine-craft", Map.of("hullId", "H-X", "type", "SAIL", "lengthMetres", 5,
                "engines", 1, "capacity", 2, "homePort", "P", "owner", "O"), WORKSHOP).getResponse().getStatus())
                .isEqualTo(403);
        String key = "it-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("trafficFileNumber", "TF-8", "craftType", "JET_SKI", "validityDays", 30);
        MvcResult first = api.post("/v1/marine-operator-licences", body, OFFICER, key);
        MvcResult again = api.post("/v1/marine-operator-licences", body, OFFICER, key);
        assertThat(again.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(api.body(again).get("id")).isEqualTo(api.body(first).get("id"));
    }
}

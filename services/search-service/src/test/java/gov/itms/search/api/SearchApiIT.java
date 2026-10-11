package gov.itms.search.api;

import static gov.itms.search.api.ContractClient.user;
import static org.assertj.core.api.Assertions.assertThat;

import gov.itms.search.infrastructure.SourceStub;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unified search through HTTP, PostgreSQL (audit outbox) and real HTTP stand-ins for the owning services;
 * contract-checked against search-service.v1.yaml.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("CC-100")
class SearchApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final SourceStub OWNERS = new SourceStub();
    private static final RequestPostProcessor STAFF = user("u-staff", "SEARCH_USER", "search.execute");
    private static final RequestPostProcessor OUTSIDER = user("u-other", "CLERK", "vehicle.read");

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        for (String url : List.of("ITMS_TRAFFIC_FILES_URL", "ITMS_VEHICLES_URL", "ITMS_VIOLATIONS_URL",
                "ITMS_ACCIDENTS_URL", "ITMS_TRANSACTIONS_URL", "ITMS_LICENSING_URL")) {
            registry.add(url, OWNERS::baseUrl);
        }
    }

    @AfterAll
    static void stop() {
        OWNERS.close();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    private ContractClient api;

    @BeforeEach
    void client() {
        api = new ContractClient(mvc, json, "/openapi/search-service.v1.yaml");
    }

    @Test
    @Tag("CC-102")
    @Tag("CC-103")
    @DisplayName("CC-100, CC-102: a VIN is asked of vehicle-service with the caller's token; a plate-shaped query also "
            + "reaches the plate lookup; a refusing owner is FORBIDDEN; the search is in the audit outbox")
    void search() throws Exception {
        OWNERS.answers.put("/v1/vehicles/WDB00017917346101", new SourceStub.Answer(200, "{\"vin\":\"WDB00017917346101\","
                + "\"region\":\"DXB\",\"status\":\"REGISTERED\",\"plateNumber\":\"S 6\",\"ownerFileNumber\":\"TF-1\"}",
                0));
        OWNERS.answers.put("/v1/traffic-files/WDB00017917346101", new SourceStub.Answer(403, "{}", 0));

        JsonNode r = api.expect(api.post("/v1/searches", Map.of("query", "WDB00017917346101"), STAFF), 200);

        assertThat(r.get("items")).hasSize(1);
        JsonNode hit = r.get("items").get(0);
        assertThat(hit.get("type").asString()).isEqualTo("VEHICLE");
        assertThat(hit.get("id").asString()).isEqualTo("WDB00017917346101");
        assertThat(hit.get("region").asString()).isEqualTo("DXB");
        assertThat(hit.get("fields").get("plateNumber").asString()).isEqualTo("S 6");
        Map<String, String> statuses = new java.util.HashMap<>();
        r.get("sources").forEach(s -> statuses.put(s.get("name").asString(), s.get("status").asString()));
        assertThat(statuses).containsEntry("vehicle-vin", "OK").containsEntry("traffic-file", "FORBIDDEN")
                .doesNotContainKeys("violation", "accident", "traffic-file-identity");
        assertThat(OWNERS.seen).filteredOn(s -> s.target().equals("/v1/vehicles/WDB00017917346101"))
                .allSatisfy(s -> assertThat(s.authorization()).startsWith("Bearer "))
                .allSatisfy(s -> assertThat(s.channel()).isEqualTo("BACKOFFICE"));

        String id = r.get("searchId").asString();
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :id and action = 'SEARCH_PERFORMED'")
                .param("id", id).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-100: types limit the record types; the permission and the query rules are enforced")
    void rules() throws Exception {
        JsonNode r = api.expect(api.post("/v1/searches", Map.of("query", "V-2026-000000001", "types",
                List.of("ACCIDENT")), STAFF), 200);
        assertThat(r.get("items")).isEmpty();
        assertThat(r.get("sources")).isEmpty();
        assertThat(api.post("/v1/searches", Map.of("query", "V-2026-1"), OUTSIDER).getResponse().getStatus())
                .isEqualTo(403);
        JsonNode bad = api.expect(api.post("/v1/searches", Map.of("query", "a%b"), STAFF), 422);
        assertThat(bad.get("code").asString()).isEqualTo("QUERY_INVALID");
        assertThat(api.post("/v1/searches", Map.of("query", "TF-1", "extra", 1), STAFF).getResponse().getStatus())
                .isEqualTo(400);
        JsonNode noChannel = api.channel("COUNTER").expect(api.post("/v1/searches", Map.of("query", "TF-1"), STAFF),
                422);
        assertThat(noChannel.get("code").asString()).isEqualTo("CHANNEL_REQUIRED");
    }
}

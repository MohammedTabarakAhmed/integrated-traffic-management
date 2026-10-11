package gov.itms.geo.api;

import static gov.itms.geo.api.ContractClient.user;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
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

/** National geography through HTTP, PostgreSQL and the outboxes; contract-checked against geo-service.v1.yaml. */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("RE-001")
class GeoApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final RequestPostProcessor ADMIN = user("u-geo", "GEO_ADMIN", "geo.node.manage", "geo.node.read");
    private static final RequestPostProcessor READER = user("u-read", "GEO_READER", "geo.node.read");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    private ContractClient api;

    @BeforeEach
    void client() {
        api = new ContractClient(mvc, json, "/openapi/geo-service.v1.yaml");
    }

    private static Map<String, Object> node(UUID parent, String type, String code) {
        Map<String, Object> m = new HashMap<>(Map.of("type", type, "code", code, "nameEn", "Name " + code,
                "nameAr", "اسم " + code, "region", "NORTH"));
        if (parent != null) {
            m.put("parentId", parent.toString());
        }
        return m;
    }

    private UUID created(Map<String, Object> body) throws Exception {
        return UUID.fromString(api.expect(api.post("/v1/geographic-nodes", body, ADMIN), 201).get("id").asString());
    }

    @Test
    @Tag("RE-002")
    @Tag("RE-005")
    @DisplayName("region → city → area → street → road segment with its attributes; children listed by type")
    void hierarchy() throws Exception {
        String s = Long.toString(System.nanoTime(), 36).toUpperCase();
        UUID region = created(node(null, "REGION", "R" + s));
        UUID city = created(node(region, "CITY", "C" + s));
        UUID area = created(node(city, "AREA", "A" + s));
        Map<String, Object> bridge = node(area, "BRIDGE", "B" + s);
        bridge.put("bridgeLoadTonnes", 40.0);
        bridge.put("latitude", 24.5);
        bridge.put("longitude", 54.4);
        created(bridge);
        UUID street = created(node(area, "STREET", "S" + s));
        Map<String, Object> segment = node(street, "ROAD_SEGMENT", "RS" + s);
        segment.putAll(Map.of("roadType", "ARTERIAL", "laneCount", 3, "direction", "FORWARD",
                "owningAuthority", "ROADS", "speedLimitKmh", 80.0, "restrictions", "no trucks at night"));
        UUID seg = created(segment);

        JsonNode got = api.expect(api.get("/v1/geographic-nodes/" + seg, READER), 200);
        assertThat(got.get("laneCount").asInt()).isEqualTo(3);
        assertThat(got.get("owningAuthority").asString()).isEqualTo("ROADS");
        assertThat(got.get("parentId").asString()).isEqualTo(street.toString());

        JsonNode children = api.expect(api.get("/v1/geographic-nodes?parentId=" + area + "&type=BRIDGE", READER), 200);
        assertThat(children.get("items")).hasSize(1);
        assertThat(api.expect(api.get("/v1/geographic-nodes?parentId=" + area, READER), 200).get("items")).hasSize(2);
        assertThat(db.sql("select count(*) from audit_outbox where action = 'GEO_NODE_CREATED' and entity_id = :id")
                .param("id", seg.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.geo.node-created' and subject = :id")
                .param("id", seg.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("wrong parent type, missing parent, road segment without attributes and unknown node are refused")
    void refusals() throws Exception {
        String s = Long.toString(System.nanoTime(), 36).toUpperCase();
        UUID region = created(node(null, "REGION", "R" + s));
        assertThat(api.post("/v1/geographic-nodes", node(region, "AREA", "X" + s), ADMIN).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(api.post("/v1/geographic-nodes", node(null, "CITY", "Y" + s), ADMIN).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(api.post("/v1/geographic-nodes", node(UUID.randomUUID(), "CITY", "Z" + s), ADMIN).getResponse()
                .getStatus()).isEqualTo(404);
        UUID city = created(node(region, "CITY", "C" + s));
        UUID area = created(node(city, "AREA", "A" + s));
        UUID street = created(node(area, "STREET", "S" + s));
        assertThat(api.post("/v1/geographic-nodes", node(street, "ROAD_SEGMENT", "Q" + s), ADMIN).getResponse()
                .getStatus()).isEqualTo(400);
        assertThat(api.get("/v1/geographic-nodes/" + UUID.randomUUID(), READER).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @Tag("CC-001")
    @DisplayName("creating needs geo.node.manage; a retried create with the same key replays")
    void permissionAndIdempotency() throws Exception {
        String s = Long.toString(System.nanoTime(), 36).toUpperCase();
        assertThat(api.post("/v1/geographic-nodes", node(null, "REGION", "R" + s), READER).getResponse().getStatus())
                .isEqualTo(403);
        String key = "it-" + UUID.randomUUID();
        MvcResult first = api.post("/v1/geographic-nodes", node(null, "REGION", "K" + s), ADMIN, key);
        MvcResult retry = api.post("/v1/geographic-nodes", node(null, "REGION", "K" + s), ADMIN, key);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(api.body(retry).get("id")).isEqualTo(api.body(first).get("id"));
    }
}

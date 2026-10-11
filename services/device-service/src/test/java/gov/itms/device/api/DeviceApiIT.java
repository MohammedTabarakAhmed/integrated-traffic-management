package gov.itms.device.api;

import static gov.itms.device.api.ContractClient.regional;
import static gov.itms.device.api.ContractClient.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gov.itms.platform.security.AuthorisationResolver;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
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
 * Enforcement devices and capture intake (VF-010..VF-012, VF-080, VF-081) with an HTTP stand-in for violation-service
 * behind a stub token endpoint; contract-checked against device-service.v1.yaml.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("VF-010")
class DeviceApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final List<JsonNode> RECORDED = new CopyOnWriteArrayList<>();
    private static final List<String> KEYS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger NUMBERS = new AtomicInteger();
    private static volatile boolean violationsDown;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer VIOLATIONS = violations();

    private static HttpServer violations() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            s.createContext("/token", ex -> respond(ex, 200,
                    "{\"access_token\":\"it-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
            s.createContext("/v1/violations", ex -> {
                JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
                if (violationsDown) {
                    respond(ex, 503, "{}");
                    return;
                }
                RECORDED.add(body);
                KEYS.add(String.valueOf(ex.getRequestHeaders().getFirst("Idempotency-Key")));
                respond(ex, 201, "{\"number\":\"V-IT-" + NUMBERS.incrementAndGet() + "\"}");
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @DynamicPropertySource
    static void partners(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + VIOLATIONS.getAddress().getPort();
        registry.add("itms.clients.violations.base-url", () -> base);
        registry.add("itms.clients.violations.token-uri", () -> base + "/token");
        registry.add("itms.clients.violations.client-id", () -> "itms-device-service");
        registry.add("itms.clients.violations.client-secret", () -> "it-secret");
    }

    @TestConfiguration
    static class Scopes {
        @Bean
        AuthorisationResolver authorisationResolver() {
            return new ContractClient.RegionalResolver();
        }
    }

    @AfterAll
    static void stop() {
        VIOLATIONS.stop(0);
    }

    private static final RequestPostProcessor ADMIN = user("u-dev", "DEVICE_ADMIN", "device.manage", "device.read");
    private static final RequestPostProcessor FEED = user("svc-camera", "DEVICE_FEED", "device.capture.ingest");
    private static final RequestPostProcessor REVIEWER = user("u-review", "CAPTURE_REVIEWER",
            "device.capture.review");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    private ContractClient api;

    @BeforeEach
    void client() {
        api = new ContractClient(mvc, json, "/openapi/device-service.v1.yaml");
        violationsDown = false;
    }

    private String device(boolean calibrated) throws Exception {
        String code = "CAM-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        api.expect(api.post("/v1/devices", Map.of("code", code, "type", "RADAR", "owner", "POLICE", "region", "NORTH",
                "latitude", 24.4, "longitude", 54.3, "road", "E11", "direction", "NORTHBOUND", "speedLimitKmh", 100),
                ADMIN), 201);
        if (calibrated) {
            JsonNode d = api.expect(api.post("/v1/devices/" + code + "/calibrations", Map.of("validFrom",
                    Instant.now().minus(1, ChronoUnit.DAYS).toString(), "validUntil",
                    Instant.now().plus(180, ChronoUnit.DAYS).toString()), ADMIN), 200);
            assertThat(d.get("calibrationExpiresAt").isNull()).isFalse();
        }
        return code;
    }

    private Map<String, Object> capture(String device, String owner) {
        Map<String, Object> m = new HashMap<>(Map.of("sourceKey", "SRC-" + UUID.randomUUID(), "deviceCode", device,
                "capturedAt", Instant.now().minusSeconds(5).toString(), "catalogueCode", "SPEED_20", "region", "NORTH",
                "plate", "A 7777", "location", "E11 km 40", "measuredSpeedKmh", 132, "evidenceId", "EV-1",
                "plateReadable", true));
        m.put("imagePresent", true);
        if (owner != null) {
            m.put("ownerFileNumber", owner);
        }
        return m;
    }

    @Test
    @Tag("VF-081")
    @Tag("VF-012")
    @DisplayName("VF-010/012/081: a clean capture from a calibrated device becomes a violation once; a repeat source "
            + "key replays")
    void cleanCaptureRecorded() throws Exception {
        String cam = device(true);
        Map<String, Object> body = capture(cam, "TF-OWNER");
        JsonNode c = api.expect(api.post("/v1/device-captures", body, FEED), 201);
        assertThat(c.get("status").asString()).isEqualTo("RECORDED");
        assertThat(c.get("violationNumber").asString()).startsWith("V-IT-");
        assertThat(RECORDED.getLast().get("source").asString()).isEqualTo("DEVICE");
        assertThat(KEYS.getLast()).isEqualTo("device-capture-" + c.get("id").asString());
        int before = RECORDED.size();
        JsonNode again = api.expect(api.post("/v1/device-captures", body, FEED), 201);
        assertThat(again.get("id")).isEqualTo(c.get("id"));
        assertThat(RECORDED).as("no second violation").hasSize(before);
        assertThat(api.expect(api.get("/v1/devices?region=NORTH", ADMIN), 200).get("items").size()).isPositive();
    }

    @Test
    @Tag("VF-011")
    @Tag("VF-014")
    @DisplayName("VF-011/014: an uncalibrated device or unknown owner sends the capture to review; the reviewer "
            + "resolves the owner and records it, or rejects it")
    void reviewQueue() throws Exception {
        String cam = device(false);
        JsonNode queued = api.expect(api.post("/v1/device-captures", capture(cam, null), FEED), 201);
        assertThat(queued.get("status").asString()).isEqualTo("REVIEW");
        assertThat(queued.get("qualityReasons").toString()).contains("CALIBRATION_INVALID",
                "RESPONSIBLE_PARTY_UNRESOLVED");
        JsonNode rejectMe = api.expect(api.post("/v1/device-captures", capture(cam, null), FEED), 201);
        assertThat(api.expect(api.get("/v1/device-captures/review-queue?region=NORTH", REVIEWER), 200).get("items")
                .size()).isGreaterThanOrEqualTo(2);
        String id = queued.get("id").asString();
        assertThat(api.post("/v1/device-captures/" + id + "/reviews", Map.of("accept", true), REVIEWER).getResponse()
                .getStatus()).as("owner still unknown").isEqualTo(422);
        JsonNode recorded = api.expect(api.post("/v1/device-captures/" + id + "/reviews", Map.of("accept", true,
                "ownerFileNumber", "TF-RESOLVED"), REVIEWER), 200);
        assertThat(recorded.get("status").asString()).isEqualTo("RECORDED");
        assertThat(api.post("/v1/device-captures/" + id + "/reviews", Map.of("accept", false), REVIEWER).getResponse()
                .getStatus()).isEqualTo(409);
        assertThat(api.expect(api.post("/v1/device-captures/" + rejectMe.get("id").asString() + "/reviews",
                Map.of("accept", false), REVIEWER), 200).get("status").asString()).isEqualTo("REJECTED");
        assertThat(api.post("/v1/device-captures/" + UUID.randomUUID() + "/reviews", Map.of("accept", false),
                REVIEWER).getResponse().getStatus()).isEqualTo(404);
        assertThat(api.get("/v1/device-captures/review-queue?region=NORTH&limit=0", REVIEWER).getResponse()
                .getStatus()).isEqualTo(422);
    }

    @Test
    @Tag("VF-080")
    @Tag("CC-001")
    @DisplayName("VF-080: duplicates, stale or misplaced captures, an unavailable violation service and other regions "
            + "are refused")
    void refusals() throws Exception {
        String cam = device(true);
        assertThat(api.post("/v1/devices", Map.of("code", cam, "type", "RADAR", "owner", "POLICE", "region", "NORTH",
                "latitude", 1, "longitude", 1, "road", "E11", "direction", "N", "speedLimitKmh", 80), ADMIN)
                .getResponse().getStatus()).isEqualTo(409);
        Map<String, Object> stale = capture(cam, "TF-1");
        stale.put("capturedAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        assertThat(api.post("/v1/device-captures", stale, FEED).getResponse().getStatus()).isEqualTo(422);
        Map<String, Object> elsewhere = capture(cam, "TF-1");
        elsewhere.put("region", "SOUTH");
        assertThat(api.post("/v1/device-captures", elsewhere, FEED).getResponse().getStatus()).isEqualTo(422);
        Map<String, Object> unknown = capture("CAM-NONE", "TF-1");
        assertThat(api.post("/v1/device-captures", unknown, FEED).getResponse().getStatus()).isEqualTo(404);
        assertThat(api.post("/v1/devices/CAM-NONE/calibrations", Map.of("validFrom", Instant.now().toString(),
                "validUntil", Instant.now().plusSeconds(60).toString()), ADMIN).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(api.post("/v1/devices/" + cam + "/calibrations", Map.of("validFrom", Instant.now().toString(),
                "validUntil", Instant.now().minusSeconds(60).toString()), ADMIN).getResponse().getStatus())
                .isEqualTo(422);

        violationsDown = true;
        assertThat(api.post("/v1/device-captures", capture(cam, "TF-1"), FEED).getResponse().getStatus())
                .isEqualTo(503);
        violationsDown = false;

        RequestPostProcessor south = regional("svc-south", "DEVICE_FEED", List.of("SOUTH"), "device.capture.ingest",
                "device.read");
        assertThat(api.post("/v1/device-captures", capture(cam, "TF-1"), south).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(api.get("/v1/devices?region=NORTH", south).getResponse().getStatus()).isEqualTo(403);
    }
}

package gov.itms.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import gov.itms.audit.application.AuditService;
import gov.itms.audit.domain.AuditRecord;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end test of the central audit trail: a row in a service's transactional outbox → the outbox relay → a real
 * Kafka broker (topic {@code itms.audit.v1}) → the consumer → the hash chain in PostgreSQL (V1 guard) → the timeline
 * API, validated against audit-service.v1.yaml. The broker's host port is fixed per run so it can advertise it.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.events.enabled=true",
        "itms.events.relay-interval=1s",
        "itms.audit-store.consumer.enabled=true",
        "itms.audit-store.retention-enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("CC-120")
class AuditApiIT {

    private static final int KAFKA_PORT = freePort();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final GenericContainer<?> KAFKA = kafka();

    private static OpenApiInteractionValidator contract;

    @SuppressWarnings("resource")
    private static GenericContainer<?> kafka() {
        GenericContainer<?> k = new GenericContainer<>("apache/kafka:4.2.1")
                .withEnv("KAFKA_NODE_ID", "1")
                .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
                .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + KAFKA_PORT)
                .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
                .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
                .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
                .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        k.setPortBindings(List.of(KAFKA_PORT + ":9092"));
        return k;
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:" + KAFKA_PORT);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    @Autowired
    AuditService audit;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/audit-service.v1.yaml").build();
    }

    private static RequestPostProcessor auditor() {
        return user("u-auditor", "AUDITOR", "audit.read", "audit.timeline.read", "audit.integrity.verify");
    }

    private static RequestPostProcessor user(String sub, String role, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub)
                .claim("roles", List.of(role))
                .claim("permissions", Arrays.asList(permissions)));
    }

    private MvcResult getAs(String path, RequestPostProcessor who) throws Exception {
        MvcResult result = mvc.perform(get(path).with(who).header("X-Channel", "BACKOFFICE")
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)).andReturn();
        int qs = path.indexOf('?');
        String requestPath = qs > 0 ? path.substring(0, qs) : path;
        SimpleRequest.Builder req = new SimpleRequest.Builder(Request.Method.GET, requestPath)
                .withHeader("Authorization", "Bearer t");
        if (qs > 0) {
            for (String pair : path.substring(qs + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                req.withQueryParam(kv[0], kv[1]);
            }
        }
        SimpleResponse response = SimpleResponse.Builder.status(result.getResponse().getStatus())
                .withContentType(result.getResponse().getContentType())
                .withBody(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).build();
        ValidationReport report = result.getResponse().getStatus() < 400
                ? contract.validate(req.build(), response)
                : contract.validateResponse(requestPath, Request.Method.GET, response);
        assertThat(report.getMessages()).as("OpenAPI contract for GET %s -> %d", requestPath,
                result.getResponse().getStatus()).isEmpty();
        return result;
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static AuditRecord record(String entityId, String action, Instant occurredAt) {
        return new AuditRecord(0, UUID.randomUUID(), occurredAt, "vehicle-service", action, "Vehicle", entityId,
                "u-officer", "officer", List.of("VEHICLE_OFFICER"), "BACKOFFICE", null, null, null, null, null, null,
                null, null);
    }

    @Test
    @Tag("CC-121")
    @DisplayName("a row in another service's outbox reaches the chain through Kafka and shows in the record's timeline")
    void outboxToTimelineThroughKafka() throws Exception {
        String vin = "VIN" + System.nanoTime();
        String auditId = UUID.randomUUID().toString();
        db.sql("""
                insert into audit_outbox (id, occurred_at, service, action, entity_type, entity_id, actor_id,
                    actor_username, actor_roles, channel, new_value, reason)
                values (cast(:id as uuid), :at, 'vehicle-service', 'VEHICLE_REGISTERED', 'Vehicle', :vin, 'u-officer',
                    'officer', cast('["VEHICLE_OFFICER"]' as jsonb), 'BACKOFFICE', cast('{"status":"ACTIVE"}' as jsonb),
                    'first registration')""")
                .param("id", auditId).param("at", Timestamp.from(Instant.now())).param("vin", vin).update();

        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).until(() ->
                db.sql("select count(*) from audit_record where audit_id = cast(:id as uuid)").param("id", auditId)
                        .query(Long.class).single() == 1);
        JsonNode timeline = body(getAs("/v1/timelines/Vehicle/" + vin, auditor()));
        JsonNode first = timeline.get("items").get(0);
        assertThat(first.get("auditId").asString()).isEqualTo(auditId);
        assertThat(first.get("service").asString()).isEqualTo("vehicle-service");
        assertThat(first.get("action").asString()).isEqualTo("VEHICLE_REGISTERED");
        assertThat(first.get("actorRoles").get(0).asString()).isEqualTo("VEHICLE_OFFICER");
        assertThat(json.readTree(first.get("newValue").asString()).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(first.get("hash").asString()).hasSize(64);
        assertThat(db.sql("select published_at is not null from audit_outbox where id = cast(:id as uuid)")
                .param("id", auditId).query(Boolean.class).single()).isTrue();

        // A redelivery of the same audit id is ignored.
        assertThat(audit.ingest(record(vin, "VEHICLE_REGISTERED", Instant.now()))).isTrue();
        long before = db.sql("select count(*) from audit_record").query(Long.class).single();
        AuditRecord again = new AuditRecord(0, UUID.fromString(auditId), Instant.now(), "vehicle-service", "X",
                "Vehicle", vin, "u", null, List.of(), "BACKOFFICE", null, null, null, null, null, null, null, null);
        assertThat(audit.ingest(again)).isFalse();
        assertThat(db.sql("select count(*) from audit_record").query(Long.class).single()).isEqualTo(before);
    }

    @Test
    @Tag("CC-122")
    @DisplayName("the guard rejects update, delete and truncate of audit records")
    void guard() {
        audit.ingest(record("VIN-G" + System.nanoTime(), "VEHICLE_REGISTERED", Instant.now()));
        assertThatThrownBy(() -> db.sql("update audit_record set reason = 'x'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never change");
        assertThatThrownBy(() -> db.sql("delete from audit_record").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("retention job");
        assertThatThrownBy(() -> db.sql("truncate audit_record").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never change");
    }

    @Test
    @Tag("CC-122")
    @DisplayName("the chain verifies intact; the retention purge deletes only records past retention")
    void integrityAndRetention() throws Exception {
        String vin = "VIN-P" + System.nanoTime();
        audit.ingest(record(vin, "VEHICLE_REGISTERED", Instant.now().minus(Duration.ofDays(3000))));
        audit.ingest(record(vin, "VEHICLE_RENEWED", Instant.now().minus(Duration.ofDays(10))));
        JsonNode verified = body(getAs("/v1/audit-integrity", auditor()));
        assertThat(verified.get("intact").asBoolean()).isTrue();
        assertThat(verified.get("checked").asLong()).isPositive();

        assertThat(audit.purge(Duration.ofDays(2922), 100_000)).isGreaterThanOrEqualTo(1);
        List<String> left = db.sql("select action from audit_record where entity_id = :v order by seq")
                .param("v", vin).query(String.class).list();
        assertThat(left).contains("VEHICLE_RENEWED").doesNotContain("VEHICLE_REGISTERED");
    }

    @Test
    @Tag("CC-121")
    @DisplayName("timelines page oldest first with nextAfterSeq; search needs a filter and the audit.read permission")
    void pagingAndSearch() throws Exception {
        String vin = "VIN-T" + System.nanoTime();
        for (String action : List.of("VEHICLE_REGISTERED", "PLATE_ASSIGNED", "VEHICLE_RENEWED")) {
            audit.ingest(record(vin, action, Instant.now()));
        }
        JsonNode page1 = body(getAs("/v1/timelines/Vehicle/" + vin + "?limit=2", auditor()));
        assertThat(page1.get("items").findValuesAsString("action")).containsExactly("VEHICLE_REGISTERED",
                "PLATE_ASSIGNED");
        long next = page1.get("nextAfterSeq").asLong();
        JsonNode page2 = body(getAs("/v1/timelines/Vehicle/" + vin + "?limit=2&afterSeq=" + next, auditor()));
        assertThat(page2.get("items").get(0).get("action").asString()).isEqualTo("VEHICLE_RENEWED");

        assertThat(getAs("/v1/audit-records?from=2026-01-01T00:00:00Z", auditor()).getResponse().getStatus())
                .isEqualTo(422);
        assertThat(body(getAs("/v1/audit-records?entityType=Vehicle&entityId=" + vin + "&limit=10", auditor()))
                .get("items")).hasSizeGreaterThanOrEqualTo(3);
        RequestPostProcessor timelineOnly = user("u-officer", "OFFICER", "audit.timeline.read");
        assertThat(getAs("/v1/audit-records?entityType=Vehicle&entityId=" + vin, timelineOnly).getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(getAs("/v1/audit-integrity", timelineOnly).getResponse().getStatus()).isEqualTo(403);
        assertThat(db.sql("select count(*) from audit_outbox where action = 'AUDIT_TIMELINE_VIEWED' and entity_id = :v")
                .param("v", vin).query(Long.class).single()).isEqualTo(2);
    }
}

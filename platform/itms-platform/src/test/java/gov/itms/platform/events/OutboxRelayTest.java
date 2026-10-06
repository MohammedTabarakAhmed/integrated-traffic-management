package gov.itms.platform.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Transactional outbox relay (INT-005, CC-120, ADR-002, ADR-040) against a real PostgreSQL. */
@Testcontainers
@Tag("INT-005")
class OutboxRelayTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static JdbcClient jdbc;
    private static TransactionTemplate tx;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);
    private final RecordingPublisher publisher = new RecordingPublisher();
    private OutboxRelay relay;

    @BeforeAll
    static void schema() throws IOException {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        for (String file : List.of("V0_1__audit_outbox.sql", "V0_2__event_outbox.sql")) {
            try (var in = OutboxRelayTest.class.getResourceAsStream("/db/platform/" + file)) {
                jdbc.sql(new String(in.readAllBytes(), StandardCharsets.UTF_8)).update();
            }
        }
    }

    @BeforeEach
    void setUp() {
        // The tables are append-only, so each test starts by marking older rows as already published.
        jdbc.sql("update audit_outbox set published_at = now() where published_at is null").update();
        jdbc.sql("update event_outbox set published_at = now() where published_at is null").update();
        relay = new OutboxRelay(jdbc, tx, publisher, json, clock, new SimpleMeterRegistry(), "itms.audit.v1",
                "itms.test-service.events.v1", 10);
    }

    private UUID audit(Instant at, String entityId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into audit_outbox (id, occurred_at, service, action, entity_type, entity_id, actor_id,
                            actor_username, actor_roles, channel, ip_address, correlation_id, old_value, new_value,
                            reason)
                        values (:id, :at, 'test-service', 'HOLD_PLACED', 'Hold', :entityId, 'user-1', 'dev.maker',
                            '["HOLD_OFFICER"]'::jsonb, 'BACKOFFICE', '10.0.0.1', 'corr-1', null,
                            '{"status":"ACTIVE"}'::jsonb, 'court order')""")
                .param("id", id).param("at", Timestamp.from(at)).param("entityId", entityId).update();
        return id;
    }

    private Instant publishedAt(String table, UUID id) {
        Timestamp at = jdbc.sql("select published_at from " + table + " where id = :id").param("id", id)
                .query(Timestamp.class).optional().orElse(null);
        return at == null ? null : at.toInstant();
    }

    @Test
    @Tag("CC-120")
    @DisplayName("audit records are sent oldest first as CloudEvents and marked published")
    void relaysAuditRecords() {
        UUID later = audit(Instant.parse("2026-10-06T09:00:02Z"), "H-2");
        UUID earlier = audit(Instant.parse("2026-10-06T09:00:01Z"), "H-1");

        assertThat(relay.relayAudit()).isEqualTo(2);

        assertThat(publisher.sent).extracting(OutboundEvent::id).containsExactly(earlier.toString(), later.toString());
        OutboundEvent first = publisher.sent.getFirst();
        assertThat(first.topic()).isEqualTo("itms.audit.v1");
        assertThat(first.key()).isEqualTo("Hold/H-1");
        JsonNode envelope = json.readTree(first.envelope());
        assertThat(envelope.get("specversion").asString()).isEqualTo("1.0");
        assertThat(envelope.get("type").asString()).isEqualTo("itms.audit.recorded");
        assertThat(envelope.get("source").asString()).isEqualTo("/itms/test-service");
        assertThat(envelope.get("subject").asString()).isEqualTo("Hold/H-1");
        assertThat(envelope.get("correlationid").asString()).isEqualTo("corr-1");
        JsonNode data = envelope.get("data");
        assertThat(data.get("action").asString()).isEqualTo("HOLD_PLACED");
        assertThat(data.get("actorRoles").get(0).asString()).isEqualTo("HOLD_OFFICER");
        assertThat(data.get("newValue").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(data.get("oldValue").isNull()).isTrue();
        assertThat(publishedAt("audit_outbox", earlier)).isEqualTo(clock.instant());
        assertThat(publishedAt("audit_outbox", later)).isEqualTo(clock.instant());

        assertThat(relay.relayAudit()).as("nothing left to send").isZero();
    }

    @Test
    @DisplayName("a broker failure stops the batch; earlier rows are marked, the failed row and later ones wait")
    void stopsAtFirstFailure() {
        UUID a = audit(Instant.parse("2026-10-06T09:00:01Z"), "H-1");
        UUID b = audit(Instant.parse("2026-10-06T09:00:02Z"), "H-1");
        UUID c = audit(Instant.parse("2026-10-06T09:00:03Z"), "H-1");
        publisher.failOn = b.toString();

        assertThat(relay.relayAudit()).isEqualTo(1);
        assertThat(publishedAt("audit_outbox", a)).isNotNull();
        assertThat(publishedAt("audit_outbox", b)).isNull();
        assertThat(publishedAt("audit_outbox", c)).isNull();

        publisher.failOn = null;
        assertThat(relay.relayAudit()).isEqualTo(2);
        assertThat(publisher.sent).extracting(OutboundEvent::id)
                .containsExactly(a.toString(), b.toString(), c.toString());
    }

    @Test
    @DisplayName("domain events recorded in a transaction are relayed to the service topic keyed by subject")
    void relaysDomainEvents() {
        var actors = new CurrentActorProvider();
        var recorder = new JdbcDomainEventRecorder(jdbc, json, actors, clock, "test-service");
        tx.executeWithoutResult(s -> recorder.recordAs(CurrentActor.system("test-job"), "itms.hold.placed",
                "HOLD-1", Map.of("holdType", "COURT_ORDER")));

        assertThat(relay.relayDomainEvents()).isEqualTo(1);
        OutboundEvent event = publisher.sent.getFirst();
        assertThat(event.topic()).isEqualTo("itms.test-service.events.v1");
        assertThat(event.key()).isEqualTo("HOLD-1");
        assertThat(event.type()).isEqualTo("itms.hold.placed");
        JsonNode envelope = json.readTree(event.envelope());
        assertThat(envelope.get("data").get("holdType").asString()).isEqualTo("COURT_ORDER");
        assertThat(envelope.get("actorid").asString()).isEqualTo("test-job");
    }

    @Test
    @DisplayName("an event recorded in a rolled-back transaction is never relayed")
    void rolledBackEventIsNotRelayed() {
        var recorder = new JdbcDomainEventRecorder(jdbc, json, new CurrentActorProvider(), clock, "test-service");
        tx.executeWithoutResult(s -> {
            recorder.recordAs(CurrentActor.system("test-job"), "itms.hold.placed", "HOLD-2", Map.of());
            s.setRollbackOnly();
        });
        assertThat(relay.relayDomainEvents()).isZero();
        assertThat(publisher.sent).isEmpty();
    }

    @Test
    @DisplayName("event types must be dotted lower-case names")
    void rejectsBadEventType() {
        var recorder = new JdbcDomainEventRecorder(jdbc, json, new CurrentActorProvider(), clock, "test-service");
        assertThatThrownBy(() -> recorder.recordAs(CurrentActor.system("x"), "HoldPlaced", "H", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("CC-122")
    @DisplayName("published rows cannot be changed or deleted, and published_at is set only once")
    void outboxStaysAppendOnly() {
        UUID id = audit(Instant.parse("2026-10-06T09:00:01Z"), "H-9");
        relay.relayAudit();
        assertThatThrownBy(() -> jdbc.sql("update audit_outbox set published_at = now() where id = :id")
                .param("id", id).update()).hasMessageContaining("append-only");

        var recorder = new JdbcDomainEventRecorder(jdbc, json, new CurrentActorProvider(), clock, "test-service");
        tx.executeWithoutResult(s -> recorder.recordAs(CurrentActor.system("x"), "itms.hold.lifted", "H-9", Map.of()));
        relay.relayDomainEvents();
        assertThatThrownBy(() -> jdbc.sql("delete from event_outbox").update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("update event_outbox set subject = 'other'").update())
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("idempotent consumers see each event id once")
    void processedEvents() {
        var processed = new ProcessedEvents(jdbc, clock);
        String eventId = UUID.randomUUID().toString();
        assertThat(processed.firstDelivery("notification-service", eventId)).isTrue();
        assertThat(processed.firstDelivery("notification-service", eventId)).isFalse();
        assertThat(processed.firstDelivery("search-indexer", eventId)).as("other consumer").isTrue();
    }

    private static final class RecordingPublisher implements EventPublisher {
        final List<OutboundEvent> sent = new ArrayList<>();
        String failOn;

        @Override
        public void publish(OutboundEvent event) {
            if (event.id().equals(failOn)) {
                throw new EventPublishingException("broker down", null);
            }
            sent.add(event);
        }
    }
}

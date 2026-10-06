package gov.itms.platform.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Transactional outbox relay (ADR-002, ADR-040, INT-005, CC-120). Sends unpublished rows of {@code audit_outbox}
 * and {@code event_outbox} to the broker, oldest first, then sets {@code published_at}.
 *
 * <p>Safe on every instance at once: rows are claimed with {@code FOR UPDATE SKIP LOCKED}. Delivery is at least
 * once (a crash after the broker acknowledged but before the commit resends the row); consumers skip duplicates by
 * event id ({@link ProcessedEvents}). On the first failure the batch stops, so a later row of the same subject is
 * never sent before an earlier one.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    static final String AUDIT_EVENT_TYPE = "itms.audit.recorded";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher publisher;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final String auditTopic;
    private final String domainTopic;
    private final int batchSize;

    public OutboxRelay(JdbcClient jdbc, TransactionTemplate tx, EventPublisher publisher, ObjectMapper json,
                       Clock clock, MeterRegistry meters, String auditTopic, String domainTopic, int batchSize) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.publisher = publisher;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
        this.auditTopic = auditTopic;
        this.domainTopic = domainTopic;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelayString = "${itms.events.relay-interval:1s}",
            fixedDelayString = "${itms.events.relay-interval:1s}")
    public void scheduled() {
        try {
            relayAudit();
            relayDomainEvents();
        } catch (RuntimeException e) {
            log.error("Outbox relay failed; unpublished rows are retried on the next run", e);
        }
    }

    /** @return the number of audit records published */
    public int relayAudit() {
        Integer published = tx.execute(status -> {
            List<OutboundEvent> batch = jdbc.sql("""
                            select id, occurred_at, service, action, entity_type, entity_id, actor_id, actor_username,
                                actor_roles::text as actor_roles, channel, ip_address, correlation_id,
                                old_value::text as old_value, new_value::text as new_value, reason
                            from audit_outbox
                            where published_at is null
                            order by occurred_at, id
                            limit :batch
                            for update skip locked""")
                    .param("batch", batchSize)
                    .query(this::auditEvent)
                    .list();
            return publishAndMark("audit_outbox", batch);
        });
        return published == null ? 0 : published;
    }

    /** @return the number of domain events published */
    public int relayDomainEvents() {
        Integer published = tx.execute(status -> {
            List<OutboundEvent> batch = jdbc.sql("""
                            select id, occurred_at, service, event_type, subject, data::text as data, actor_id,
                                correlation_id
                            from event_outbox
                            where published_at is null
                            order by occurred_at, id
                            limit :batch
                            for update skip locked""")
                    .param("batch", batchSize)
                    .query(this::domainEvent)
                    .list();
            return publishAndMark("event_outbox", batch);
        });
        return published == null ? 0 : published;
    }

    private int publishAndMark(String table, List<OutboundEvent> batch) {
        List<UUID> sent = new ArrayList<>(batch.size());
        for (OutboundEvent event : batch) {
            try {
                publisher.publish(event);
                sent.add(UUID.fromString(event.id()));
            } catch (EventPublishingException e) {
                log.warn("Broker did not accept {} row {}; {} sent before it, the rest wait for the next run",
                        table, event.id(), sent.size(), e);
                meters.counter("itms.outbox.failures", "outbox", table).increment();
                break;
            }
        }
        if (!sent.isEmpty()) {
            // Fixed table names only; never caller input.
            jdbc.sql("update " + table + " set published_at = :now where id in (:ids)")
                    .param("now", Timestamp.from(clock.instant()))
                    .param("ids", sent)
                    .update();
            meters.counter("itms.outbox.published", "outbox", table).increment(sent.size());
        }
        return sent.size();
    }

    private OutboundEvent auditEvent(ResultSet rs, int row) throws SQLException {
        String id = rs.getString("id");
        Instant time = rs.getTimestamp("occurred_at").toInstant();
        String service = rs.getString("service");
        String subject = rs.getString("entity_type") + "/" + rs.getString("entity_id");

        ObjectNode data = json.createObjectNode();
        data.put("auditId", id);
        data.put("occurredAt", time.toString());
        data.put("service", service);
        data.put("action", rs.getString("action"));
        data.put("entityType", rs.getString("entity_type"));
        data.put("entityId", rs.getString("entity_id"));
        data.put("actorId", rs.getString("actor_id"));
        data.put("actorUsername", rs.getString("actor_username"));
        data.set("actorRoles", json.readTree(rs.getString("actor_roles")));
        data.put("channel", rs.getString("channel"));
        data.put("ipAddress", rs.getString("ip_address"));
        data.put("correlationId", rs.getString("correlation_id"));
        setJson(data, "oldValue", rs.getString("old_value"));
        setJson(data, "newValue", rs.getString("new_value"));
        data.put("reason", rs.getString("reason"));

        String envelope = CloudEvents.envelope(json, id, service, AUDIT_EVENT_TYPE, subject, time,
                json.writeValueAsString(data), rs.getString("actor_id"), rs.getString("correlation_id"));
        return new OutboundEvent(id, auditTopic, subject, AUDIT_EVENT_TYPE, time, envelope);
    }

    private OutboundEvent domainEvent(ResultSet rs, int row) throws SQLException {
        String id = rs.getString("id");
        Instant time = rs.getTimestamp("occurred_at").toInstant();
        String type = rs.getString("event_type");
        String subject = rs.getString("subject");
        String envelope = CloudEvents.envelope(json, id, rs.getString("service"), type, subject, time,
                rs.getString("data"), rs.getString("actor_id"), rs.getString("correlation_id"));
        return new OutboundEvent(id, domainTopic, subject, type, time, envelope);
    }

    private void setJson(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.set(field, json.readTree(value));
        }
    }
}

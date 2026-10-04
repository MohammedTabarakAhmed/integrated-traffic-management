package gov.itms.platform.audit;

import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes audit records to {@code audit_outbox} using the current transaction's connection, so the audit record and
 * the business change commit or roll back together (NFR-015, CC-120). The table is append-only (CC-122).
 */
public class JdbcAuditRecorder implements AuditRecorder {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final CurrentActorProvider actors;
    private final Clock clock;
    private final String serviceName;

    public JdbcAuditRecorder(JdbcClient jdbc, ObjectMapper json, CurrentActorProvider actors, Clock clock,
                             String serviceName) {
        this.jdbc = jdbc;
        this.json = json;
        this.actors = actors;
        this.clock = clock;
        this.serviceName = serviceName;
    }

    @Override
    public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                       String reason) {
        CurrentActor actor = actors.current();
        jdbc.sql("""
                        insert into audit_outbox (id, occurred_at, service, action, entity_type, entity_id,
                            actor_id, actor_username, actor_roles, channel, ip_address, correlation_id,
                            old_value, new_value, reason)
                        values (:id, :at, :service, :action, :entityType, :entityId,
                            :actorId, :actorUsername, cast(:roles as jsonb), :channel, :ip, :correlationId,
                            cast(:oldValue as jsonb), cast(:newValue as jsonb), :reason)""")
                .param("id", UUID.randomUUID())
                .param("at", Timestamp.from(clock.instant()))
                .param("service", serviceName)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("actorId", actor.userId())
                .param("actorUsername", actor.username())
                .param("roles", json.writeValueAsString(actor.roles()))
                .param("channel", actor.channel())
                .param("ip", actor.ipAddress())
                .param("correlationId", actor.correlationId())
                .param("oldValue", oldValue == null ? null : json.writeValueAsString(oldValue))
                .param("newValue", newValue == null ? null : json.writeValueAsString(newValue))
                .param("reason", reason)
                .update();
    }
}

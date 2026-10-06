package gov.itms.platform.events;

import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Writes domain events to {@code event_outbox} with the current transaction's connection (ADR-040). */
public class JdbcDomainEventRecorder implements DomainEventRecorder {

    /** Dotted lower-case names such as {@code itms.vehicle.registered}. */
    static final Pattern EVENT_TYPE = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final CurrentActorProvider actors;
    private final Clock clock;
    private final String serviceName;

    public JdbcDomainEventRecorder(JdbcClient jdbc, ObjectMapper json, CurrentActorProvider actors, Clock clock,
                                   String serviceName) {
        this.jdbc = jdbc;
        this.json = json;
        this.actors = actors;
        this.clock = clock;
        this.serviceName = serviceName;
    }

    @Override
    public void record(String eventType, String subject, Object data) {
        recordAs(actors.current(), eventType, subject, data);
    }

    @Override
    public void recordAs(CurrentActor actor, String eventType, String subject, Object data) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(data, "data");
        if (eventType == null || !EVENT_TYPE.matcher(eventType).matches()) {
            throw new IllegalArgumentException("Invalid event type: " + eventType);
        }
        jdbc.sql("""
                        insert into event_outbox (id, occurred_at, service, event_type, subject, data, actor_id,
                            correlation_id)
                        values (:id, :at, :service, :type, :subject, cast(:data as jsonb), :actorId, :correlationId)""")
                .param("id", UUID.randomUUID())
                .param("at", Timestamp.from(clock.instant()))
                .param("service", serviceName)
                .param("type", eventType)
                .param("subject", subject)
                .param("data", json.writeValueAsString(data))
                .param("actorId", actor.userId())
                .param("correlationId", actor.correlationId())
                .update();
    }
}

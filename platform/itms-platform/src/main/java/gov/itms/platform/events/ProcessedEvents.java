package gov.itms.platform.events;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Idempotent consumers (ADR-002): call {@link #firstDelivery} in the same transaction as the consumer's own change.
 * A redelivered event returns {@code false} and the consumer does nothing.
 */
public class ProcessedEvents {

    private final JdbcClient jdbc;
    private final Clock clock;

    public ProcessedEvents(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return {@code true} the first time this consumer sees the event id, {@code false} on every redelivery */
    public boolean firstDelivery(String consumer, String eventId) {
        return jdbc.sql("""
                        insert into processed_event (consumer, event_id, processed_at)
                        values (:consumer, :eventId, :at)
                        on conflict do nothing""")
                .param("consumer", consumer)
                .param("eventId", eventId)
                .param("at", Timestamp.from(clock.instant()))
                .update() == 1;
    }
}

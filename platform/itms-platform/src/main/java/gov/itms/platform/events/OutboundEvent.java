package gov.itms.platform.events;

import java.time.Instant;
import java.util.Objects;

/**
 * One event ready to leave the service, already wrapped as a CloudEvents 1.0 JSON document (ADR-002).
 *
 * @param id       the event id; consumers use it to skip redeliveries
 * @param topic    the broker topic
 * @param key      the partition key; events with the same key keep their order
 * @param type     the CloudEvents type, for example {@code itms.audit.recorded}
 * @param time     when the change happened
 * @param envelope the CloudEvents JSON document sent as the message value
 */
public record OutboundEvent(String id, String topic, String key, String type, Instant time, String envelope) {

    public OutboundEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(envelope, "envelope");
    }
}

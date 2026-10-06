package gov.itms.platform.events;

/**
 * The message broker port (ADR-002, INT-005). Services and the outbox relay depend on this interface, never on a
 * broker API, so the Authority's own bus can replace Kafka by adding another implementation (Q-009).
 */
public interface EventPublisher {

    /**
     * Sends the event and returns only once the broker has acknowledged it.
     *
     * @throws EventPublishingException if the broker did not acknowledge the event
     */
    void publish(OutboundEvent event);
}

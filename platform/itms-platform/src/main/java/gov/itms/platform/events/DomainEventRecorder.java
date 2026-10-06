package gov.itms.platform.events;

import gov.itms.platform.security.CurrentActor;

/**
 * Records a domain event (INT-005, for example {@code VehicleRegistered}) in the service's {@code event_outbox}, in
 * the caller's transaction, so the event exists if and only if the business change commits (ADR-002, ADR-040).
 * {@link OutboxRelay} sends it to the broker afterwards.
 */
public interface DomainEventRecorder {

    /**
     * @param eventType the event name in the ITMS catalogue, for example {@code itms.vehicle.registered}
     * @param subject   the business key the event is about; events with the same subject keep their order
     * @param data      the payload, serialised as JSON; must not carry secrets or full sensitive values (SEC-051)
     */
    void record(String eventType, String subject, Object data);

    /** As {@link #record}, for work outside a user request (scheduled jobs, consumers). */
    void recordAs(CurrentActor actor, String eventType, String subject, Object data);
}

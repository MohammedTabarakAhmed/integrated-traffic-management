package gov.itms.platform.audit;

import gov.itms.platform.security.CurrentActor;

/**
 * Records an audit event in the caller's transaction (CC-120). Implementations write to the service's append-only
 * {@code audit_outbox}; a relay later forwards the records to the central audit service (ADR-017).
 */
public interface AuditRecorder {

    /**
     * @param action     verb in UPPER_SNAKE_CASE, e.g. {@code REFDATA_ITEM_UPDATED}
     * @param entityType business entity type, e.g. {@code ReferenceItem}
     * @param entityId   business identifier of the entity
     * @param oldValue   state before the change, or {@code null}
     * @param newValue   state after the change, or {@code null}
     * @param reason     user-supplied or system reason, or {@code null}
     */
    void record(String action, String entityType, String entityId, Object oldValue, Object newValue, String reason);

    /**
     * Same as {@link #record}, for actions without a signed-in user (start-up bootstrap, scheduled jobs). The actor is
     * explicit, typically {@link CurrentActor#system(String)}.
     */
    void recordAs(CurrentActor actor, String action, String entityType, String entityId, Object oldValue,
                  Object newValue, String reason);
}

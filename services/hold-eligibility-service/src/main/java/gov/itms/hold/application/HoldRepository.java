package gov.itms.hold.application;

import gov.itms.hold.domain.Hold;
import gov.itms.hold.domain.Subject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port to the hold store (owned by this service only, ADR-016). */
public interface HoldRepository {

    /**
     * Stores a new hold. Throws a {@code DUPLICATE_SOURCE_REF} conflict when its source already used the same
     * reference (CC-033), also when two requests race.
     */
    void insert(Hold hold);

    Optional<Hold> find(UUID id);

    /** The hold, locked until the end of the database transaction. */
    Optional<Hold> findForUpdate(UUID id);

    /** Every hold on the subject, newest first. */
    List<Hold> findBySubject(Subject subject);

    /** Stores the lift; the stored version must be the one before it (optimistic check, NFR-015). */
    void saveLift(Hold hold);

    /** Every stored version of the hold, oldest first (DAT-002). */
    List<HoldVersion> history(UUID id);

    /** One row of the temporal history: the hold as it was from {@code validFrom} until {@code validTo}. */
    record HoldVersion(Hold hold, Instant validFrom, Instant validTo, String changedBy) {
    }
}

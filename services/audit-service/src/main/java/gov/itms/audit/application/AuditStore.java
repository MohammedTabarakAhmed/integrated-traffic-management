package gov.itms.audit.application;

import gov.itms.audit.domain.AuditRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The central, append-only audit store (CC-120..CC-122). */
public interface AuditStore {

    /** Filters of a search; {@code null} means any. Results are oldest first after {@code afterSeq}. */
    record Query(String entityType, String entityId, String actorId, String service, String action, Instant from,
                 Instant to, Long afterSeq, int limit) {
    }

    /** Serialises appends to the chain. */
    void lockChain();

    boolean exists(java.util.UUID auditId);

    /** The last record of the chain, if any. */
    Optional<AuditRecord> last();

    void append(AuditRecord record);

    List<AuditRecord> search(Query query);

    /** Records in chain order from {@code fromSeq} (inclusive), at most {@code limit}. */
    List<AuditRecord> chain(long fromSeq, int limit);

    Optional<AuditRecord> first();

    /** Deletes records that occurred before {@code before} (retention, CC-122); returns how many. */
    int purge(Instant before, int limit);
}

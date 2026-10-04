package gov.itms.refdata.application;

import gov.itms.refdata.domain.ReferenceItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for reference items and their temporal history (DAT-002). */
public interface ReferenceItemRepository {

    /** Exact lookup; {@code regionCode == null} means the national item. */
    Optional<ReferenceItem> find(String listCode, String code, String regionCode);

    /** National items of the list plus, when {@code regionCode} is given, that region's variants. */
    List<ReferenceItem> findByList(String listCode, String regionCode);

    void insert(ReferenceItem item, String actorId, UUID changeId, Instant at);

    /**
     * Writes {@code item} (whose version is already incremented) only if the stored version equals
     * {@code expectedVersion}. Returns false when the item changed meanwhile.
     */
    boolean update(ReferenceItem item, long expectedVersion, String actorId, UUID changeId, Instant at);

    /** All versions, oldest first. */
    List<ItemVersion> history(String listCode, String code, String regionCode);

    /** One historical version of an item and the period during which it was the current state. */
    record ItemVersion(ReferenceItem item, Instant validFrom, Instant validTo, String changedBy, UUID changeId) {
    }
}

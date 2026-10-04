package gov.itms.refdata.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A maker-checker change to reference data (CC-114). Rules enforced here:
 * <ul>
 *   <li>the checker who approves or rejects must not be the maker (CC-002);</li>
 *   <li>only a pending change can be decided;</li>
 *   <li>rejection needs a reason.</li>
 * </ul>
 * Exactly one of {@code listName} (CREATE_LIST) or {@code itemData} (CREATE_ITEM / UPDATE_ITEM) is set.
 */
public final class ChangeRequest {

    private final UUID id;
    private final ChangeType type;
    private final String listCode;
    private final String itemCode;
    private final String regionCode;
    private final BilingualName listName;
    private final String parentListCode;
    private final ItemData itemData;
    private final Long baseVersion;
    private final String reason;
    private final Actor maker;
    private final Instant submittedAt;
    private ChangeStatus status;
    private Actor checker;
    private Instant decidedAt;
    private String decisionReason;

    /** Identity of a maker or checker. */
    public record Actor(String id, String username) {
        public Actor {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("actor id required");
            }
        }
    }

    @SuppressWarnings("java:S107") // full rehydration constructor used by the repository
    public ChangeRequest(UUID id, ChangeType type, String listCode, String itemCode, String regionCode,
                         BilingualName listName, String parentListCode, ItemData itemData, Long baseVersion,
                         String reason, Actor maker, Instant submittedAt, ChangeStatus status, Actor checker,
                         Instant decidedAt, String decisionReason) {
        this.id = Objects.requireNonNull(id);
        this.type = Objects.requireNonNull(type);
        this.listCode = Codes.require("listCode", listCode);
        this.itemCode = Codes.optional("itemCode", itemCode);
        this.regionCode = Codes.optional("regionCode", regionCode);
        this.listName = listName;
        this.parentListCode = Codes.optional("parentListCode", parentListCode);
        this.itemData = itemData;
        this.baseVersion = baseVersion;
        this.reason = requireReason(reason);
        this.maker = Objects.requireNonNull(maker);
        this.submittedAt = Objects.requireNonNull(submittedAt);
        this.status = Objects.requireNonNull(status);
        this.checker = checker;
        this.decidedAt = decidedAt;
        this.decisionReason = decisionReason;
        checkShape();
    }

    public static ChangeRequest createList(String listCode, BilingualName name, String parentListCode, String reason,
                                           Actor maker, Instant now) {
        return new ChangeRequest(UUID.randomUUID(), ChangeType.CREATE_LIST, listCode, null, null, name, parentListCode,
                null, null, reason, maker, now, ChangeStatus.PENDING_APPROVAL, null, null, null);
    }

    public static ChangeRequest createItem(String listCode, String itemCode, String regionCode, ItemData data,
                                           String reason, Actor maker, Instant now) {
        return new ChangeRequest(UUID.randomUUID(), ChangeType.CREATE_ITEM, listCode, itemCode, regionCode, null, null,
                data, null, reason, maker, now, ChangeStatus.PENDING_APPROVAL, null, null, null);
    }

    public static ChangeRequest updateItem(String listCode, String itemCode, String regionCode, ItemData data,
                                           long baseVersion, String reason, Actor maker, Instant now) {
        return new ChangeRequest(UUID.randomUUID(), ChangeType.UPDATE_ITEM, listCode, itemCode, regionCode, null, null,
                data, baseVersion, reason, maker, now, ChangeStatus.PENDING_APPROVAL, null, null, null);
    }

    private void checkShape() {
        switch (type) {
            case CREATE_LIST -> {
                if (listName == null || itemCode != null || itemData != null || regionCode != null) {
                    throw DomainException.validation("CHANGE_SHAPE_INVALID",
                            "CREATE_LIST needs listCode and name only (plus optional parentListCode)");
                }
            }
            case CREATE_ITEM, UPDATE_ITEM -> {
                if (itemCode == null || itemData == null || listName != null || parentListCode != null) {
                    throw DomainException.validation("CHANGE_SHAPE_INVALID",
                            type + " needs listCode, itemCode, name and effectiveFrom");
                }
                if (type == ChangeType.UPDATE_ITEM && (baseVersion == null || baseVersion < 1)) {
                    throw DomainException.validation("BASE_VERSION_REQUIRED",
                            "UPDATE_ITEM needs the baseVersion of the item being changed");
                }
            }
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.strip().length() < 3) {
            throw DomainException.validation("REASON_REQUIRED", "a reason of at least 3 characters is required");
        }
        return reason.strip();
    }

    /** Approve (CC-114). The checker must not be the maker (CC-002). */
    public void approve(Actor checker, Instant now) {
        decide(checker, now);
        this.status = ChangeStatus.APPROVED;
    }

    /** Reject with a reason. The checker must not be the maker (CC-002). */
    public void reject(Actor checker, String reason, Instant now) {
        String clean = requireReason(reason);
        decide(checker, now);
        this.status = ChangeStatus.REJECTED;
        this.decisionReason = clean;
    }

    private void decide(Actor decider, Instant now) {
        Objects.requireNonNull(decider);
        if (status != ChangeStatus.PENDING_APPROVAL) {
            throw DomainException.conflict("CHANGE_NOT_PENDING", "the change has already been " + status, status);
        }
        if (decider.id().equals(maker.id())) {
            throw new DomainException(DomainException.Kind.SEGREGATION_OF_DUTIES, "SEGREGATION_OF_DUTIES",
                    "the person who submitted a change cannot approve or reject it", List.of("CC-002", "CC-114"));
        }
        this.checker = decider;
        this.decidedAt = Objects.requireNonNull(now);
    }

    public UUID id() { return id; }
    public ChangeType type() { return type; }
    public String listCode() { return listCode; }
    public String itemCode() { return itemCode; }
    public String regionCode() { return regionCode; }
    public BilingualName listName() { return listName; }
    public String parentListCode() { return parentListCode; }
    public ItemData itemData() { return itemData; }
    public Long baseVersion() { return baseVersion; }
    public String reason() { return reason; }
    public Actor maker() { return maker; }
    public Instant submittedAt() { return submittedAt; }
    public ChangeStatus status() { return status; }
    public Actor checker() { return checker; }
    public Instant decidedAt() { return decidedAt; }
    public String decisionReason() { return decisionReason; }
}

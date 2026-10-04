package gov.itms.transaction.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A maker-checker change to the service catalogue (CC-114, CC-020, CC-026). Rules enforced here:
 * <ul>
 *   <li>each change type carries exactly its own content (see {@link Type});</li>
 *   <li>the checker who approves or rejects must not be the maker (CC-002);</li>
 *   <li>only a pending change can be decided, once; a rejection needs a reason.</li>
 * </ul>
 */
public final class CatalogueChange {

    /** What a change does, and the content it needs. */
    public enum Type {
        /** {@code service}, whose code equals {@code serviceCode}. */
        CREATE_SERVICE,
        /** {@code service} and {@code baseVersion}, the version the maker saw. */
        UPDATE_SERVICE,
        /** {@code rule}. */
        ADD_AVAILABILITY_RULE,
        /** {@code ruleId} and {@code endDate}. */
        END_AVAILABILITY_RULE
    }

    public enum Status { PENDING_APPROVAL, APPROVED, REJECTED }

    private final UUID id;
    private final Type type;
    private final String serviceCode;
    private final ServiceSpec service;
    private final Long baseVersion;
    private final AvailabilityRuleSpec rule;
    private final UUID ruleId;
    private final LocalDate endDate;
    private final String reason;
    private final Actor maker;
    private final Instant submittedAt;
    private Status status;
    private Actor checker;
    private Instant decidedAt;
    private String decisionReason;

    @SuppressWarnings("java:S107") // full rehydration constructor used by the repository
    public CatalogueChange(UUID id, Type type, String serviceCode, ServiceSpec service, Long baseVersion,
                           AvailabilityRuleSpec rule, UUID ruleId, LocalDate endDate, String reason, Actor maker,
                           Instant submittedAt, Status status, Actor checker, Instant decidedAt,
                           String decisionReason) {
        this.id = Objects.requireNonNull(id);
        this.type = Objects.requireNonNull(type);
        this.serviceCode = Codes.serviceCode(serviceCode);
        this.service = service;
        this.baseVersion = baseVersion;
        this.rule = rule;
        this.ruleId = ruleId;
        this.endDate = endDate;
        this.reason = Codes.reason(reason);
        this.maker = Objects.requireNonNull(maker);
        this.submittedAt = Objects.requireNonNull(submittedAt);
        this.status = Objects.requireNonNull(status);
        this.checker = checker;
        this.decidedAt = decidedAt;
        this.decisionReason = decisionReason;
        checkShape();
    }

    /** A new pending change from a maker. */
    @SuppressWarnings("java:S107")
    public static CatalogueChange submit(Type type, String serviceCode, ServiceSpec service, Long baseVersion,
                                         AvailabilityRuleSpec rule, UUID ruleId, LocalDate endDate, String reason,
                                         Actor maker, Instant now) {
        if (type == null) {
            throw DomainException.validation("CHANGE_TYPE_REQUIRED", "a change type is required");
        }
        return new CatalogueChange(UUID.randomUUID(), type, serviceCode, service, baseVersion, rule, ruleId, endDate,
                reason, maker, now, Status.PENDING_APPROVAL, null, null, null);
    }

    private void checkShape() {
        boolean ok = switch (type) {
            case CREATE_SERVICE -> service != null && baseVersion == null && rule == null && ruleId == null
                    && endDate == null;
            case UPDATE_SERVICE -> service != null && baseVersion != null && baseVersion >= 1 && rule == null
                    && ruleId == null && endDate == null;
            case ADD_AVAILABILITY_RULE -> rule != null && service == null && baseVersion == null && ruleId == null
                    && endDate == null;
            case END_AVAILABILITY_RULE -> ruleId != null && endDate != null && service == null && baseVersion == null
                    && rule == null;
        };
        if (!ok) {
            throw DomainException.validation("CHANGE_SHAPE_INVALID", shapeHint(type), type);
        }
        if (service != null && !service.code().equals(serviceCode)) {
            throw DomainException.validation("SERVICE_CODE_MISMATCH",
                    "service.code must equal serviceCode of the change");
        }
    }

    private static String shapeHint(Type type) {
        return switch (type) {
            case CREATE_SERVICE -> "CREATE_SERVICE needs service only";
            case UPDATE_SERVICE -> "UPDATE_SERVICE needs service and baseVersion";
            case ADD_AVAILABILITY_RULE -> "ADD_AVAILABILITY_RULE needs rule only";
            case END_AVAILABILITY_RULE -> "END_AVAILABILITY_RULE needs ruleId and endDate";
        };
    }

    /** Approve (CC-114). The checker must not be the maker (CC-002). */
    public void approve(Actor decider, Instant now) {
        decide(decider, now);
        this.status = Status.APPROVED;
    }

    /** Reject with a reason. The checker must not be the maker (CC-002). */
    public void reject(Actor decider, String why, Instant now) {
        String clean = Codes.reason(why);
        decide(decider, now);
        this.status = Status.REJECTED;
        this.decisionReason = clean;
    }

    private void decide(Actor decider, Instant now) {
        Objects.requireNonNull(decider);
        if (status != Status.PENDING_APPROVAL) {
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
    public Type type() { return type; }
    public String serviceCode() { return serviceCode; }
    public ServiceSpec service() { return service; }
    public Long baseVersion() { return baseVersion; }
    public AvailabilityRuleSpec rule() { return rule; }
    public UUID ruleId() { return ruleId; }
    public LocalDate endDate() { return endDate; }
    public String reason() { return reason; }
    public Actor maker() { return maker; }
    public Instant submittedAt() { return submittedAt; }
    public Status status() { return status; }
    public Actor checker() { return checker; }
    public Instant decidedAt() { return decidedAt; }
    public String decisionReason() { return decisionReason; }
}

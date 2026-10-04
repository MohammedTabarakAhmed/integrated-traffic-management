package gov.itms.iam.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Two roles that one subject must not hold at the same time (CC-002). Which pairs apply is configuration (Q-030),
 * never code. Rules enforced here:
 * <ul>
 *   <li>the role codes are stored in alphabetical order, so (A, B) and (B, A) are the same pair;</li>
 *   <li>a role cannot conflict with itself;</li>
 *   <li>pairs are never deleted; a pair that no longer applies is retired once, with a reason.</li>
 * </ul>
 */
public final class RoleConflict {

    public enum Status { ACTIVE, RETIRED }

    private final UUID id;
    private final String roleA;
    private final String roleB;
    private final String reason;
    private final Actor createdBy;
    private final Instant createdAt;
    private Status status;
    private Actor retiredBy;
    private Instant retiredAt;
    private String retirementReason;

    @SuppressWarnings("java:S107") // full rehydration constructor used by the repository
    public RoleConflict(UUID id, String roleA, String roleB, String reason, Actor createdBy, Instant createdAt,
                        Status status, Actor retiredBy, Instant retiredAt, String retirementReason) {
        this.id = Objects.requireNonNull(id);
        this.roleA = Codes.roleCode(roleA);
        this.roleB = Codes.roleCode(roleB);
        if (this.roleA.compareTo(this.roleB) >= 0) {
            throw new IllegalArgumentException("roleA must sort before roleB");
        }
        this.reason = Codes.reason(reason);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.status = Objects.requireNonNull(status);
        this.retiredBy = retiredBy;
        this.retiredAt = retiredAt;
        this.retirementReason = retirementReason;
    }

    /** A new ACTIVE pair; the two codes may be given in either order. */
    public static RoleConflict declare(String role1, String role2, String reason, Actor actor, Instant now) {
        String first = Codes.roleCode(role1);
        String second = Codes.roleCode(role2);
        if (first.equals(second)) {
            throw DomainException.validation("ROLE_CONFLICT_SAME_ROLE", "a role cannot conflict with itself", first);
        }
        boolean ordered = first.compareTo(second) < 0;
        return new RoleConflict(UUID.randomUUID(), ordered ? first : second, ordered ? second : first, reason, actor,
                now, Status.ACTIVE, null, null, null);
    }

    /** Stops enforcing the pair; it stays in the history. */
    public void retire(Actor actor, String why, Instant now) {
        String clean = Codes.reason(why);
        if (status != Status.ACTIVE) {
            throw DomainException.conflict("ROLE_CONFLICT_NOT_ACTIVE", "the role conflict has already been retired");
        }
        this.status = Status.RETIRED;
        this.retiredBy = Objects.requireNonNull(actor);
        this.retiredAt = Objects.requireNonNull(now);
        this.retirementReason = clean;
    }

    /** Whether this pair contains {@code roleCode}. */
    public boolean involves(String roleCode) {
        return roleA.equals(roleCode) || roleB.equals(roleCode);
    }

    /** The other role of the pair; {@code roleCode} must be one of the two. */
    public String otherThan(String roleCode) {
        if (!involves(roleCode)) {
            throw new IllegalArgumentException(roleCode + " is not part of " + roleA + "/" + roleB);
        }
        return roleA.equals(roleCode) ? roleB : roleA;
    }

    public UUID id() { return id; }
    public String roleA() { return roleA; }
    public String roleB() { return roleB; }
    public String reason() { return reason; }
    public Actor createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public Status status() { return status; }
    public Actor retiredBy() { return retiredBy; }
    public Instant retiredAt() { return retiredAt; }
    public String retirementReason() { return retirementReason; }
}

package gov.itms.iam.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A role held by a subject within data scopes for a validity period (CC-001). Rules enforced here:
 * <ul>
 *   <li>nobody assigns a role to themselves (CC-002);</li>
 *   <li>the validity period must end after it starts and must not already be over;</li>
 *   <li>assignments are never deleted; they end by date or by revocation, which happens once and needs a reason.</li>
 * </ul>
 */
public final class RoleAssignment {

    public enum Status { ACTIVE, REVOKED }

    private final UUID id;
    private final String subjectId;
    private final String subjectUsername;
    private final String roleCode;
    private final AssignmentScopes scopes;
    private final Instant validFrom;
    private final Instant validTo;
    private final String reason;
    private final Actor assignedBy;
    private final Instant assignedAt;
    private Status status;
    private Actor revokedBy;
    private Instant revokedAt;
    private String revocationReason;

    @SuppressWarnings("java:S107") // full rehydration constructor used by the repository
    public RoleAssignment(UUID id, String subjectId, String subjectUsername, String roleCode, AssignmentScopes scopes,
                          Instant validFrom, Instant validTo, String reason, Actor assignedBy, Instant assignedAt,
                          Status status, Actor revokedBy, Instant revokedAt, String revocationReason) {
        this.id = Objects.requireNonNull(id);
        this.subjectId = Codes.subjectId(subjectId);
        this.subjectUsername = subjectUsername == null || subjectUsername.isBlank() ? null : subjectUsername.strip();
        this.roleCode = Codes.roleCode(roleCode);
        this.scopes = Objects.requireNonNull(scopes);
        this.validFrom = Objects.requireNonNull(validFrom);
        this.validTo = validTo;
        this.reason = Codes.reason(reason);
        this.assignedBy = Objects.requireNonNull(assignedBy);
        this.assignedAt = Objects.requireNonNull(assignedAt);
        this.status = Objects.requireNonNull(status);
        this.revokedBy = revokedBy;
        this.revokedAt = revokedAt;
        this.revocationReason = revocationReason;
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw DomainException.validation("VALIDITY_INVALID", "the end of the validity period must be after its start");
        }
    }

    /** A new assignment. {@code validFrom} defaults to {@code now}. */
    public static RoleAssignment assign(String subjectId, String subjectUsername, String roleCode,
                                        AssignmentScopes scopes, Instant validFrom, Instant validTo, String reason,
                                        Actor assignedBy, Instant now) {
        if (assignedBy.id().equals(subjectId)) {
            throw DomainException.segregationOfDuties("SELF_ASSIGNMENT",
                    "nobody can assign a role to themselves; another administrator must do it");
        }
        Instant from = validFrom != null ? validFrom : now;
        if (validTo != null && !validTo.isAfter(from)) {
            throw DomainException.validation("VALIDITY_INVALID", "the end of the validity period must be after its start");
        }
        if (validTo != null && !validTo.isAfter(now)) {
            throw DomainException.validation("VALIDITY_IN_PAST", "the validity period would already be over");
        }
        return new RoleAssignment(UUID.randomUUID(), subjectId, subjectUsername, roleCode, scopes, from, validTo,
                reason, assignedBy, now, Status.ACTIVE, null, null, null);
    }

    /** Ends the assignment now, with a reason. */
    public void revoke(Actor actor, String why, Instant now) {
        String clean = Codes.reason(why);
        if (status != Status.ACTIVE) {
            throw DomainException.conflict("ASSIGNMENT_NOT_ACTIVE", "the assignment has already been revoked");
        }
        this.status = Status.REVOKED;
        this.revokedBy = Objects.requireNonNull(actor);
        this.revokedAt = Objects.requireNonNull(now);
        this.revocationReason = clean;
    }

    /** Active, and {@code at} lies inside the validity period (end exclusive). */
    public boolean isEffectiveAt(Instant at) {
        return status == Status.ACTIVE && !at.isBefore(validFrom) && (validTo == null || at.isBefore(validTo));
    }

    public UUID id() { return id; }
    public String subjectId() { return subjectId; }
    public String subjectUsername() { return subjectUsername; }
    public String roleCode() { return roleCode; }
    public AssignmentScopes scopes() { return scopes; }
    public Instant validFrom() { return validFrom; }
    public Instant validTo() { return validTo; }
    public String reason() { return reason; }
    public Actor assignedBy() { return assignedBy; }
    public Instant assignedAt() { return assignedAt; }
    public Status status() { return status; }
    public Actor revokedBy() { return revokedBy; }
    public Instant revokedAt() { return revokedAt; }
    public String revocationReason() { return revocationReason; }
}

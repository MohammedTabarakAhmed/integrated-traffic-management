package gov.itms.iam.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A role held by a subject within data scopes for a validity period (CC-001). Rules enforced here:
 * <ul>
 *   <li>nobody assigns a role to themselves (CC-002);</li>
 *   <li>the validity period must end after it starts and must not already be over;</li>
 *   <li>assignments are never deleted; they end by date or by revocation, which happens once and needs a reason;</li>
 *   <li>a delegation (CC-004) copies a DIRECT assignment's role and scopes to another subject for a set period that
 *       ends no later than the source; it is never delegated again.</li>
 * </ul>
 */
public final class RoleAssignment {

    public enum Status { ACTIVE, REVOKED }

    /** DIRECT = assigned by an administrator; DELEGATION = delegated from another assignment (CC-004). */
    public enum Source { DIRECT, DELEGATION }

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
    private final Source source;
    private final UUID delegatedFrom;
    private Status status;
    private Actor revokedBy;
    private Instant revokedAt;
    private String revocationReason;

    @SuppressWarnings("java:S107") // full rehydration constructor used by the repository
    public RoleAssignment(UUID id, String subjectId, String subjectUsername, String roleCode, AssignmentScopes scopes,
                          Instant validFrom, Instant validTo, String reason, Actor assignedBy, Instant assignedAt,
                          Status status, Actor revokedBy, Instant revokedAt, String revocationReason,
                          Source source, UUID delegatedFrom) {
        this.id = Objects.requireNonNull(id);
        this.subjectId = Codes.subjectId(subjectId);
        this.subjectUsername = subjectUsername == null || subjectUsername.isBlank() ? null : subjectUsername.strip();
        this.roleCode = Codes.roleCode(roleCode);
        this.scopes = Objects.requireNonNull(scopes);
        this.validFrom = Instants.stored(Objects.requireNonNull(validFrom));
        this.validTo = Instants.stored(validTo);
        this.reason = Codes.reason(reason);
        this.assignedBy = Objects.requireNonNull(assignedBy);
        this.assignedAt = Instants.stored(Objects.requireNonNull(assignedAt));
        this.status = Objects.requireNonNull(status);
        this.revokedBy = revokedBy;
        this.revokedAt = Instants.stored(revokedAt);
        this.revocationReason = revocationReason;
        this.source = Objects.requireNonNull(source);
        this.delegatedFrom = delegatedFrom;
        if ((source == Source.DELEGATION) != (delegatedFrom != null)) {
            throw new IllegalArgumentException("a delegation, and only a delegation, has a source assignment");
        }
        if (this.validTo != null && !this.validTo.isAfter(this.validFrom)) {
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
        Instant from = checkWindow(validFrom, validTo, now);
        return new RoleAssignment(UUID.randomUUID(), subjectId, subjectUsername, roleCode, scopes, from, validTo,
                reason, assignedBy, now, Status.ACTIVE, null, null, null, Source.DIRECT, null);
    }

    /**
     * Delegates {@code source} to {@code delegateId} until {@code validTo} (CC-004). Who may delegate (the holder or
     * an in-scope administrator) is decided by the caller of this method; the rules on the delegation itself are
     * enforced here. {@code validFrom} defaults to {@code now}.
     */
    @SuppressWarnings("java:S107") // every field of the delegation request
    public static RoleAssignment delegate(RoleAssignment source, String delegateId, String delegateUsername,
                                          Instant validFrom, Instant validTo, String reason, Actor delegator,
                                          Instant now) {
        String delegate = Codes.subjectId(delegateId);
        if (source.source == Source.DELEGATION) {
            throw DomainException.validation("REDELEGATION", "a delegated role cannot be delegated again");
        }
        if (!source.isActiveOrUpcomingAt(now)) {
            throw DomainException.conflict("SOURCE_NOT_ACTIVE", "the assignment to delegate has ended or was revoked");
        }
        if (delegate.equals(delegator.id())) {
            throw DomainException.segregationOfDuties("SELF_DELEGATION", "nobody can delegate a role to themselves");
        }
        if (delegate.equals(source.subjectId)) {
            throw DomainException.validation("DELEGATE_IS_HOLDER", "the delegate already holds this assignment");
        }
        if (validTo == null) {
            throw DomainException.validation("DELEGATION_END_REQUIRED", "a delegation needs an end date and time");
        }
        if (source.validTo != null && validTo.isAfter(source.validTo)) {
            throw DomainException.validation("DELEGATION_EXCEEDS_SOURCE",
                    "a delegation cannot end after the assignment it delegates");
        }
        Instant from = checkWindow(validFrom, validTo, now);
        return new RoleAssignment(UUID.randomUUID(), delegate, delegateUsername, source.roleCode, source.scopes, from,
                validTo, reason, delegator, now, Status.ACTIVE, null, null, null, Source.DELEGATION, source.id);
    }

    private static Instant checkWindow(Instant validFrom, Instant validTo, Instant now) {
        Instant from = validFrom != null ? validFrom : now;
        if (validTo != null && !validTo.isAfter(from)) {
            throw DomainException.validation("VALIDITY_INVALID", "the end of the validity period must be after its start");
        }
        if (validTo != null && !validTo.isAfter(now)) {
            throw DomainException.validation("VALIDITY_IN_PAST", "the validity period would already be over");
        }
        return from;
    }

    /** Ends the assignment now, with a reason. */
    public void revoke(Actor actor, String why, Instant now) {
        String clean = Codes.reason(why);
        if (status != Status.ACTIVE) {
            throw DomainException.conflict("ASSIGNMENT_NOT_ACTIVE", "the assignment has already been revoked");
        }
        this.status = Status.REVOKED;
        this.revokedBy = Objects.requireNonNull(actor);
        this.revokedAt = Instants.stored(Objects.requireNonNull(now));
        this.revocationReason = clean;
    }

    /** Active, and {@code at} lies inside the validity period (end exclusive). */
    public boolean isEffectiveAt(Instant at) {
        return status == Status.ACTIVE && !at.isBefore(validFrom) && (validTo == null || at.isBefore(validTo));
    }

    /** Active and not yet ended at {@code at} (it may start later). */
    public boolean isActiveOrUpcomingAt(Instant at) {
        return status == Status.ACTIVE && (validTo == null || at.isBefore(validTo));
    }

    public boolean isDelegation() {
        return source == Source.DELEGATION;
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
    public Source source() { return source; }
    public UUID delegatedFrom() { return delegatedFrom; }
}

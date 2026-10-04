package gov.itms.iam.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One role assignment under review (SEC-007, ADR-026), with a snapshot of the assignment taken when the review
 * opened, so the evidence stays readable as it was. Rules enforced here:
 * <ul>
 *   <li>only an ACTIVE DIRECT assignment is reviewed (delegations are not, Q-039);</li>
 *   <li>a PENDING item is decided once: KEEP (reason optional) or REVOKE (reason required) (Q-040);</li>
 *   <li>nobody reviews their own access, nor an assignment they granted (CC-002, Q-040);</li>
 *   <li>an item still PENDING when the review closes becomes NOT_REVIEWED, or ENDED if its assignment has ended
 *       meanwhile; nothing is revoked automatically (Q-037).</li>
 * </ul>
 * Whether the reviewer's scope covers the item and whether the review is open are checked by the caller.
 */
public final class AccessReviewItem {

    public enum Status { PENDING, KEPT, REVOKED, NOT_REVIEWED, ENDED }

    public enum Decision { KEEP, REVOKE }

    private final UUID id;
    private final UUID campaignId;
    private final Instant campaignDueAt;
    private final UUID assignmentId;
    private final String subjectId;
    private final String subjectUsername;
    private final String roleCode;
    private final AssignmentScopes scopes;
    private final Actor assignedBy;
    private final Instant validFrom;
    private final Instant validTo;
    private Status status;
    private Actor decidedBy;
    private Instant decidedAt;
    private String decisionReason;

    @SuppressWarnings("java:S107") // full rehydration constructor used by the repository
    public AccessReviewItem(UUID id, UUID campaignId, Instant campaignDueAt, UUID assignmentId, String subjectId,
                            String subjectUsername, String roleCode, AssignmentScopes scopes, Actor assignedBy,
                            Instant validFrom, Instant validTo, Status status, Actor decidedBy, Instant decidedAt,
                            String decisionReason) {
        this.id = Objects.requireNonNull(id);
        this.campaignId = Objects.requireNonNull(campaignId);
        this.campaignDueAt = Instants.stored(Objects.requireNonNull(campaignDueAt));
        this.assignmentId = Objects.requireNonNull(assignmentId);
        this.subjectId = Codes.subjectId(subjectId);
        this.subjectUsername = subjectUsername;
        this.roleCode = Codes.roleCode(roleCode);
        this.scopes = Objects.requireNonNull(scopes);
        this.assignedBy = Objects.requireNonNull(assignedBy);
        this.validFrom = Instants.stored(Objects.requireNonNull(validFrom));
        this.validTo = Instants.stored(validTo);
        this.status = Objects.requireNonNull(status);
        this.decidedBy = decidedBy;
        this.decidedAt = Instants.stored(decidedAt);
        this.decisionReason = decisionReason;
    }

    /** A PENDING item for {@code assignment} in {@code campaign}, with a snapshot of the assignment. */
    public static AccessReviewItem forAssignment(AccessReviewCampaign campaign, RoleAssignment assignment) {
        if (assignment.isDelegation() || assignment.status() != RoleAssignment.Status.ACTIVE) {
            throw new IllegalArgumentException("only an ACTIVE DIRECT assignment can be reviewed");
        }
        return new AccessReviewItem(UUID.randomUUID(), campaign.id(), campaign.dueAt(), assignment.id(),
                assignment.subjectId(), assignment.subjectUsername(), assignment.roleCode(), assignment.scopes(),
                assignment.assignedBy(), assignment.validFrom(), assignment.validTo(), Status.PENDING, null, null,
                null);
    }

    /**
     * Refuses a decision by {@code reviewer} that breaks the item's own rules: already decided, the reviewer's own
     * access, or an assignment the reviewer granted.
     */
    public void requireDecidableBy(Actor reviewer) {
        if (status != Status.PENDING) {
            throw DomainException.conflict("ACCESS_REVIEW_ITEM_DECIDED", "this review item has already been decided");
        }
        if (reviewer.id().equals(subjectId)) {
            throw DomainException.segregationOfDuties("ACCESS_REVIEW_SELF", "nobody can review their own access");
        }
        if (reviewer.id().equals(assignedBy.id())) {
            throw DomainException.segregationOfDuties("ACCESS_REVIEW_OWN_GRANT",
                    "nobody can review a role assignment they granted");
        }
    }

    /** Records the reviewer's decision. A REVOKE needs a reason; for a KEEP it is optional. */
    public void decide(Decision decision, Actor reviewer, String reason, Instant now) {
        Objects.requireNonNull(decision, "decision required");
        requireDecidableBy(reviewer);
        String clean = decision == Decision.REVOKE || (reason != null && !reason.isBlank())
                ? Codes.reason(reason) : null;
        this.status = decision == Decision.KEEP ? Status.KEPT : Status.REVOKED;
        this.decidedBy = reviewer;
        this.decidedAt = Objects.requireNonNull(now);
        this.decisionReason = clean;
    }

    /**
     * Outcome of an item still PENDING when its review closes: NOT_REVIEWED if the assignment is still in force,
     * otherwise ENDED. Revokes nothing.
     */
    public void closeUnreviewed(boolean assignmentStillActive) {
        if (status != Status.PENDING) {
            throw DomainException.conflict("ACCESS_REVIEW_ITEM_DECIDED", "this review item has already been decided");
        }
        this.status = assignmentStillActive ? Status.NOT_REVIEWED : Status.ENDED;
    }

    /** PENDING and past the review's due date. */
    public boolean isOverdue(Instant now) {
        return status == Status.PENDING && now.isAfter(campaignDueAt);
    }

    /** Decided after the review's due date. */
    public boolean decidedLate() {
        return decidedAt != null && decidedAt.isAfter(campaignDueAt);
    }

    public UUID id() { return id; }
    public UUID campaignId() { return campaignId; }
    public Instant campaignDueAt() { return campaignDueAt; }
    public UUID assignmentId() { return assignmentId; }
    public String subjectId() { return subjectId; }
    public String subjectUsername() { return subjectUsername; }
    public String roleCode() { return roleCode; }
    public AssignmentScopes scopes() { return scopes; }
    public Actor assignedBy() { return assignedBy; }
    public Instant validFrom() { return validFrom; }
    public Instant validTo() { return validTo; }
    public Status status() { return status; }
    public Actor decidedBy() { return decidedBy; }
    public Instant decidedAt() { return decidedAt; }
    public String decisionReason() { return decisionReason; }
}

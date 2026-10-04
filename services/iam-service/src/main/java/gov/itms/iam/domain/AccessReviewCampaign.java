package gov.itms.iam.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An access review over a data scope (SEC-007, ADR-026), opened by an administrator with a due date. Rules enforced
 * here:
 * <ul>
 *   <li>a title of 3 to 200 characters, valid scopes, a reason and a due date in the future are required (the BRD
 *       sets no review deadline, so the due date is input, Q-038);</li>
 *   <li>campaigns are never deleted; a campaign is closed once, with a reason;</li>
 *   <li>an OPEN campaign is overdue once its due date has passed; nothing is revoked because of it (Q-037).</li>
 * </ul>
 */
public final class AccessReviewCampaign {

    public enum Status { OPEN, CLOSED }

    private final UUID id;
    private final String title;
    private final AssignmentScopes scopes;
    private final Instant dueAt;
    private final String reason;
    private final Actor createdBy;
    private final Instant createdAt;
    private Status status;
    private Actor closedBy;
    private Instant closedAt;
    private String closureReason;

    @SuppressWarnings("java:S107") // full rehydration constructor used by the repository
    public AccessReviewCampaign(UUID id, String title, AssignmentScopes scopes, Instant dueAt, String reason,
                                Actor createdBy, Instant createdAt, Status status, Actor closedBy, Instant closedAt,
                                String closureReason) {
        this.id = Objects.requireNonNull(id);
        this.title = title(title);
        this.scopes = Objects.requireNonNull(scopes);
        this.dueAt = Instants.stored(Objects.requireNonNull(dueAt));
        this.reason = Codes.reason(reason);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Instants.stored(Objects.requireNonNull(createdAt));
        this.status = Objects.requireNonNull(status);
        this.closedBy = closedBy;
        this.closedAt = Instants.stored(closedAt);
        this.closureReason = closureReason;
    }

    /** A new OPEN campaign. */
    public static AccessReviewCampaign open(String title, AssignmentScopes scopes, Instant dueAt, String reason,
                                            Actor actor, Instant now) {
        String cleanTitle = title(title);
        if (scopes == null) {
            throw DomainException.validation("SCOPE_REQUIRED", "regions needs at least one value (or *)", "regions");
        }
        if (dueAt == null) {
            throw DomainException.validation("ACCESS_REVIEW_DUE_REQUIRED", "a review needs a due date");
        }
        if (!Instants.stored(dueAt).isAfter(now)) {
            throw DomainException.validation("ACCESS_REVIEW_DUE_IN_PAST", "the due date of a review must be in the future");
        }
        return new AccessReviewCampaign(UUID.randomUUID(), cleanTitle, scopes, dueAt, reason, actor, now,
                Status.OPEN, null, null, null);
    }

    private static String title(String value) {
        String clean = value == null ? "" : value.strip();
        if (clean.codePointCount(0, clean.length()) < 3 || clean.length() > 200) {
            throw DomainException.validation("ACCESS_REVIEW_TITLE_INVALID",
                    "the title of a review must be 3 to 200 characters");
        }
        return clean;
    }

    /** Refuses any change unless the campaign is OPEN. */
    public void requireOpen() {
        if (status != Status.OPEN) {
            throw DomainException.conflict("ACCESS_REVIEW_CLOSED", "the access review is closed");
        }
    }

    /** Closes the campaign, once, with a reason. What happens to undecided items is decided by the caller. */
    public void close(Actor actor, String why, Instant now) {
        String clean = Codes.reason(why);
        requireOpen();
        this.status = Status.CLOSED;
        this.closedBy = Objects.requireNonNull(actor);
        this.closedAt = Instants.stored(Objects.requireNonNull(now));
        this.closureReason = clean;
    }

    /** OPEN and past its due date. */
    public boolean isOverdue(Instant now) {
        return status == Status.OPEN && now.isAfter(dueAt);
    }

    public UUID id() { return id; }
    public String title() { return title; }
    public AssignmentScopes scopes() { return scopes; }
    public Instant dueAt() { return dueAt; }
    public String reason() { return reason; }
    public Actor createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public Status status() { return status; }
    public Actor closedBy() { return closedBy; }
    public Instant closedAt() { return closedAt; }
    public String closureReason() { return closureReason; }
}

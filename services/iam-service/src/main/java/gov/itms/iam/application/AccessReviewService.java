package gov.itms.iam.application;

import gov.itms.iam.domain.AccessReviewCampaign;
import gov.itms.iam.domain.AccessReviewItem;
import gov.itms.iam.domain.AccessReviewPolicy;
import gov.itms.iam.domain.Actor;
import gov.itms.iam.domain.AssignmentScopes;
import gov.itms.iam.domain.Codes;
import gov.itms.iam.domain.DomainException;
import gov.itms.iam.domain.Instants;
import gov.itms.iam.domain.RoleAssignment;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quarterly access reviews by managers (SEC-007 manager half, ADR-026). Every change is audited in the same
 * transaction as the data it changes (CC-120). Data-scope rules (CC-001):
 * <ul>
 *   <li>opening and closing a review needs {@value #REVIEW_MANAGE} covering the review's scopes;</li>
 *   <li>a review is visible when {@value #REVIEW_READ} covers its scopes; an item when it covers the item's scopes,
 *       so a regional reviewer works the items of a national review without seeing its header;</li>
 *   <li>deciding an item needs {@value #REVIEW_DECIDE} covering the item's scopes. A REVOKE decision revokes the
 *       assignment itself: the review decision is the authority, not {@code iam.assignment.manage} (Q-040).</li>
 * </ul>
 * Undecided items are flagged overdue and are never revoked automatically (Q-037).
 */
public class AccessReviewService {

    public static final String REVIEW_MANAGE = "iam.review.manage";
    public static final String REVIEW_READ = "iam.review.read";
    public static final String REVIEW_DECIDE = "iam.review.decide";

    private final AccessReviewRepository reviews;
    private final AssignmentRepository assignments;
    private final AuditRecorder audit;
    private final AccessReviewPolicy policy;
    private final Clock clock;

    /** Items of a campaign by outcome; {@code overdue} counts the PENDING items once the due date has passed. */
    public record Counts(int total, int pending, int overdue, int kept, int revoked, int notReviewed, int ended) {
    }

    /**
     * A campaign with its counts. {@code skippedUnderReview} is only set in the result of opening it: assignments in
     * scope that were already PENDING in another review.
     */
    public record Review(AccessReviewCampaign campaign, Counts counts, boolean overdue, Integer skippedUnderReview) {
    }

    /** An item with whether it is overdue now. */
    public record ReviewItem(AccessReviewItem item, boolean overdue) {
    }

    /** An assignment due for review: not kept by a review within the period since it was granted or last kept. */
    public record OverdueAssignment(RoleAssignment assignment, Instant lastReviewedAt, Instant reviewDueSince,
                                    UUID pendingItemId) {
    }

    public record OverdueAssignments(Period reviewPeriod, Instant evaluatedAt, List<OverdueAssignment> assignments) {
        public OverdueAssignments {
            assignments = List.copyOf(assignments);
        }
    }

    public AccessReviewService(AccessReviewRepository reviews, AssignmentRepository assignments, AuditRecorder audit,
                               AccessReviewPolicy policy, Clock clock) {
        this.reviews = reviews;
        this.assignments = assignments;
        this.audit = audit;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Opens a review: every ACTIVE DIRECT assignment that has not ended and lies within {@code scopes} becomes a
     * PENDING item, except those already PENDING in another review.
     */
    @Transactional
    public Review open(String title, AssignmentScopes scopes, Instant dueAt, String reason, Actor actor,
                       Authorisation caller) {
        Instant now = now();
        AccessReviewCampaign campaign = AccessReviewCampaign.open(title, scopes, dueAt, reason, actor, now);
        Scopes reviewScopes = IamService.toScopes(campaign.scopes());
        if (!caller.covers(REVIEW_MANAGE, reviewScopes)) {
            throw DomainException.outOfScope("SCOPE_EXCEEDED",
                    "you can only open reviews within your own regions, centres and modules");
        }
        reviews.insertCampaign(campaign);
        List<AccessReviewItem> items = assignments.activeDirect(now).stream()
                .filter(a -> reviewScopes.covers(IamService.toScopes(a.scopes())))
                .map(a -> AccessReviewItem.forAssignment(campaign, a))
                .toList();
        int inserted = reviews.insertItemsSkippingPending(items);
        int skipped = items.size() - inserted;
        Map<String, Object> after = snapshot(campaign);
        after.put("itemCount", inserted);
        after.put("skippedUnderReview", skipped);
        audit.record("IAM_ACCESS_REVIEW_OPENED", "AccessReview", campaign.id().toString(), null, after,
                campaign.reason());
        return new Review(campaign, counts(campaign, now), campaign.isOverdue(now), skipped);
    }

    /** Reviews within the caller's {@value #REVIEW_READ} scope, newest first. */
    @Transactional(readOnly = true)
    public List<Review> reviews(AccessReviewCampaign.Status status, Authorisation caller) {
        Instant now = now();
        return reviews.searchCampaigns(status).stream()
                .filter(c -> canRead(c, caller))
                .map(c -> view(c, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public Review review(UUID id, Authorisation caller) {
        AccessReviewCampaign campaign = reviews.findCampaign(id).filter(c -> canRead(c, caller))
                .orElseThrow(() -> reviewNotFound(id));
        return view(campaign, now());
    }

    /**
     * Closes a review. PENDING items become ENDED if their assignment has ended meanwhile, otherwise NOT_REVIEWED.
     * Nothing is revoked (Q-037).
     */
    @Transactional
    public Review close(UUID id, String reason, Actor actor, Authorisation caller) {
        String why = Codes.reason(reason);
        AccessReviewCampaign campaign = reviews.findCampaignForUpdate(id)
                .filter(c -> canRead(c, caller) || caller.covers(REVIEW_MANAGE, IamService.toScopes(c.scopes())))
                .orElseThrow(() -> reviewNotFound(id));
        if (!caller.covers(REVIEW_MANAGE, IamService.toScopes(campaign.scopes()))) {
            throw DomainException.outOfScope("SCOPE_EXCEEDED",
                    "you can only close reviews within your own regions, centres and modules");
        }
        Map<String, Object> before = snapshot(campaign);
        Instant now = now();
        campaign.close(actor, why, now);
        // Campaign first: the database lets items become NOT_REVIEWED / ENDED only in a CLOSED review (V5).
        reviews.saveClosure(campaign);
        List<String> notReviewed = new ArrayList<>();
        for (AccessReviewItem item : reviews.searchItems(id, AccessReviewItem.Status.PENDING, null)) {
            boolean stillActive = assignments.find(item.assignmentId())
                    .map(a -> a.isActiveOrUpcomingAt(now)).orElse(false);
            item.closeUnreviewed(stillActive);
            reviews.saveItemDecision(item);
            if (item.status() == AccessReviewItem.Status.NOT_REVIEWED) {
                notReviewed.add(item.id().toString());
            }
        }
        Counts counts = counts(campaign, now);
        Map<String, Object> after = snapshot(campaign);
        after.put("counts", counts);
        after.put("notReviewedItemIds", notReviewed);
        audit.record("IAM_ACCESS_REVIEW_CLOSED", "AccessReview", id.toString(), before, after, why);
        return new Review(campaign, counts, false, null);
    }

    /**
     * Items within the caller's {@value #REVIEW_READ} scope: the reviewer's queue across reviews. {@code status}
     * defaults to PENDING.
     */
    @Transactional(readOnly = true)
    public List<ReviewItem> items(UUID campaignId, AccessReviewItem.Status status, String subjectId,
                                  Authorisation caller) {
        Instant now = now();
        return reviews.searchItems(campaignId, status == null ? AccessReviewItem.Status.PENDING : status,
                        subjectId == null ? null : Codes.subjectId(subjectId)).stream()
                .filter(i -> caller.covers(REVIEW_READ, IamService.toScopes(i.scopes())))
                .map(i -> new ReviewItem(i, i.isOverdue(now)))
                .toList();
    }

    /**
     * A reviewer keeps or revokes the assignment under review. REVOKE revokes the assignment in the same transaction
     * (audited as {@code IAM_ROLE_REVOKED}); delegations made from it stop granting at once (ADR-024).
     */
    @Transactional
    public ReviewItem decide(UUID itemId, AccessReviewItem.Decision decision, String reason, Actor actor,
                             Authorisation caller) {
        AccessReviewItem seen = reviews.findItem(itemId)
                .filter(i -> caller.covers(REVIEW_READ, IamService.toScopes(i.scopes())))
                .orElseThrow(() -> itemNotFound(itemId));
        if (!caller.covers(REVIEW_DECIDE, IamService.toScopes(seen.scopes()))) {
            throw DomainException.outOfScope("SCOPE_EXCEEDED",
                    "you can only decide reviews within your own regions, centres and modules");
        }
        // Lock order: campaign (share, against closure), subject (against leaver deactivation, assign, delegate),
        // item, assignment.
        AccessReviewCampaign campaign = reviews.findCampaignForShare(seen.campaignId())
                .orElseThrow(() -> itemNotFound(itemId));
        campaign.requireOpen();
        assignments.lockSubjects(List.of(seen.subjectId()));
        AccessReviewItem item = reviews.findItemForUpdate(itemId).orElseThrow(() -> itemNotFound(itemId));
        item.requireDecidableBy(actor);
        Instant now = now();
        RoleAssignment assignment = assignments.findForUpdate(item.assignmentId())
                .filter(a -> a.isActiveOrUpcomingAt(now))
                .orElseThrow(() -> DomainException.conflict("ACCESS_REVIEW_ASSIGNMENT_ENDED",
                        "the role assignment under review has already ended or been revoked"));
        Map<String, Object> before = snapshot(item);
        item.decide(decision, actor, reason, now);
        if (decision == AccessReviewItem.Decision.REVOKE) {
            Map<String, Object> assignmentBefore = IamService.snapshot(assignment);
            assignment.revoke(actor, item.decisionReason(), now);
            assignments.saveRevocation(assignment);
            audit.record("IAM_ROLE_REVOKED", "RoleAssignment", assignment.id().toString(), assignmentBefore,
                    IamService.snapshot(assignment), assignment.revocationReason());
        }
        reviews.saveItemDecision(item);
        audit.record("IAM_ACCESS_REVIEW_DECIDED", "AccessReviewItem", itemId.toString(), before, snapshot(item),
                item.decisionReason());
        return new ReviewItem(item, item.isOverdue(now));
    }

    /**
     * ACTIVE DIRECT assignments within the caller's {@value #REVIEW_READ} scope that were granted more than one
     * review period ago and have not been KEPT by a review within the last period.
     */
    @Transactional(readOnly = true)
    public OverdueAssignments overdueAssignments(Authorisation caller) {
        Instant now = now();
        Instant since = policy.reviewedSince(now);
        List<RoleAssignment> candidates = assignments.activeDirect(now).stream()
                .filter(a -> a.assignedAt().isBefore(since))
                .filter(a -> caller.covers(REVIEW_READ, IamService.toScopes(a.scopes())))
                .toList();
        List<UUID> ids = candidates.stream().map(RoleAssignment::id).toList();
        Map<UUID, Instant> lastKept = ids.isEmpty() ? Map.of() : reviews.lastKeptAt(ids);
        Map<UUID, UUID> pending = ids.isEmpty() ? Map.of() : reviews.pendingItemIds(ids);
        List<OverdueAssignment> overdue = new ArrayList<>();
        for (RoleAssignment a : candidates) {
            Instant kept = lastKept.get(a.id());
            if (kept == null || kept.isBefore(since)) {
                overdue.add(new OverdueAssignment(a, kept, policy.dueAfter(kept == null ? a.assignedAt() : kept),
                        pending.get(a.id())));
            }
        }
        return new OverdueAssignments(policy.period(), now, overdue);
    }

    // ---------------------------------------------------------------- internals

    private static boolean canRead(AccessReviewCampaign c, Authorisation caller) {
        return caller.covers(REVIEW_READ, IamService.toScopes(c.scopes()));
    }

    private Review view(AccessReviewCampaign campaign, Instant now) {
        return new Review(campaign, counts(campaign, now), campaign.isOverdue(now), null);
    }

    private Counts counts(AccessReviewCampaign campaign, Instant now) {
        Map<AccessReviewItem.Status, Integer> byStatus = reviews.counts(campaign.id());
        int pending = byStatus.getOrDefault(AccessReviewItem.Status.PENDING, 0);
        int total = byStatus.values().stream().mapToInt(Integer::intValue).sum();
        // Every item of a campaign shares the campaign's due date.
        int overdue = now.isAfter(campaign.dueAt()) ? pending : 0;
        return new Counts(total, pending, overdue, byStatus.getOrDefault(AccessReviewItem.Status.KEPT, 0),
                byStatus.getOrDefault(AccessReviewItem.Status.REVOKED, 0),
                byStatus.getOrDefault(AccessReviewItem.Status.NOT_REVIEWED, 0),
                byStatus.getOrDefault(AccessReviewItem.Status.ENDED, 0));
    }

    private Instant now() {
        return Instants.stored(clock.instant());
    }

    private static DomainException reviewNotFound(UUID id) {
        return DomainException.notFound("ACCESS_REVIEW_NOT_FOUND", "access review " + id + " does not exist");
    }

    private static DomainException itemNotFound(UUID id) {
        return DomainException.notFound("ACCESS_REVIEW_ITEM_NOT_FOUND", "access review item " + id + " does not exist");
    }

    /** Audit-friendly view of a campaign (keeps nulls, stable key order). */
    static Map<String, Object> snapshot(AccessReviewCampaign c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id().toString());
        m.put("title", c.title());
        m.put("scopes", c.scopes());
        m.put("dueAt", c.dueAt().toString());
        m.put("status", c.status().name());
        m.put("createdBy", c.createdBy().id());
        m.put("closedBy", c.closedBy() == null ? null : c.closedBy().id());
        m.put("closureReason", c.closureReason());
        return m;
    }

    /** Audit-friendly view of an item (keeps nulls, stable key order). */
    static Map<String, Object> snapshot(AccessReviewItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id().toString());
        m.put("campaignId", i.campaignId().toString());
        m.put("assignmentId", i.assignmentId().toString());
        m.put("subjectId", i.subjectId());
        m.put("roleCode", i.roleCode());
        m.put("scopes", i.scopes());
        m.put("status", i.status().name());
        m.put("decidedBy", i.decidedBy() == null ? null : i.decidedBy().id());
        m.put("decidedAt", i.decidedAt() == null ? null : i.decidedAt().toString());
        m.put("decisionReason", i.decisionReason());
        return m;
    }
}

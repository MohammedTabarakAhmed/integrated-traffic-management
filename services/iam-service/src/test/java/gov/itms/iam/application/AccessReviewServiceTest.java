package gov.itms.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.iam.domain.AccessReviewCampaign;
import gov.itms.iam.domain.AccessReviewItem;
import gov.itms.iam.domain.AccessReviewPolicy;
import gov.itms.iam.domain.Actor;
import gov.itms.iam.domain.AssignmentScopes;
import gov.itms.iam.domain.BilingualName;
import gov.itms.iam.domain.DomainException;
import gov.itms.iam.domain.Permission;
import gov.itms.iam.domain.Role;
import gov.itms.iam.domain.RoleAssignment;
import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Quarterly access reviews (SEC-007 manager half, ADR-026) against in-memory ports. */
class AccessReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
    private static final Actor ADMIN = new Actor("u-admin", "admin");
    private static final Actor REVIEWER = new Actor("u-reviewer", "reviewer");
    private static final Actor FEED = new Actor("svc-feed", "feed");
    private static final BilingualName NAME = new BilingualName("دور", "Role");
    private static final Set<String> ADMIN_PERMISSIONS = Set.of("iam.assignment.read", "iam.assignment.manage",
            "iam.review.manage", "iam.review.read");
    private static final Set<String> REVIEWER_PERMISSIONS = Set.of("iam.review.read", "iam.review.decide");

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private Clock clock;
    private InMemoryRepositories.Assignments assignments;
    private InMemoryRepositories.AccessReviews reviews;
    private InMemoryRepositories.Audit audit;
    private IamService iam;
    private AccessReviewService service;

    @BeforeEach
    void setUp() {
        clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        InMemoryRepositories.Permissions permissions = new InMemoryRepositories.Permissions();
        InMemoryRepositories.Roles roles = new InMemoryRepositories.Roles();
        assignments = new InMemoryRepositories.Assignments();
        reviews = new InMemoryRepositories.AccessReviews(assignments);
        audit = new InMemoryRepositories.Audit();
        iam = new IamService(permissions, roles, assignments, new InMemoryRepositories.ApprovalLimits(),
                new InMemoryRepositories.RoleConflicts(), audit, clock);
        service = new AccessReviewService(reviews, assignments, audit, AccessReviewPolicy.parse("P3M"), clock);
        for (String p : List.of("refdata.read", "iam.review.read", "iam.review.decide")) {
            permissions.rows.put(p, new Permission(p, "svc", NAME));
        }
        roles.insert(Role.create("REFDATA_MAKER", NAME, null, List.of("refdata.read")), "seed", NOW);
        roles.insert(Role.create("ACCESS_REVIEWER", NAME, null, List.copyOf(REVIEWER_PERMISSIONS)), "seed", NOW);
    }

    // ------------------------------------------------------------------ helpers

    private static Consumer<Throwable> code(String expected) {
        return t -> assertThat(((DomainException) t).code()).isEqualTo(expected);
    }

    private static Scopes regions(String... regions) {
        return new Scopes(Set.of(regions), Set.of("*"), Set.of("*"));
    }

    private static AssignmentScopes scopes(String... regions) {
        return AssignmentScopes.of(List.of(regions), List.of("*"), List.of("*"));
    }

    private static Authorisation admin(Scopes scopes) {
        return new Authorisation(ADMIN.id(), List.of(new Authorisation.Grant("IT_ADMINISTRATOR", ADMIN_PERMISSIONS,
                scopes, null)), NOW);
    }

    private static Authorisation reviewer(Scopes scopes) {
        return new Authorisation(REVIEWER.id(), List.of(new Authorisation.Grant("ACCESS_REVIEWER",
                REVIEWER_PERMISSIONS, scopes, null)), NOW);
    }

    private static Authorisation grants(String subject, Authorisation.Grant... grants) {
        return new Authorisation(subject, List.of(grants), NOW);
    }

    private RoleAssignment assign(String subject, AssignmentScopes scopes) {
        return assign(subject, scopes, null, null, ADMIN);
    }

    private RoleAssignment assign(String subject, AssignmentScopes scopes, Instant from, Instant to, Actor by) {
        return iam.assign(subject, subject + ".name", "REFDATA_MAKER", scopes, from, to, "new joiner", by,
                admin(Scopes.NATIONAL));
    }

    private AccessReviewService.Review open(String... regions) {
        return service.open("Quarterly review", scopes(regions), now.get().plusSeconds(14L * 86_400),
                "quarterly access review", ADMIN, admin(Scopes.NATIONAL));
    }

    private AccessReviewItem itemFor(AccessReviewService.Review review, RoleAssignment assignment) {
        return reviews.searchItems(review.campaign().id(), null, null).stream()
                .filter(i -> i.assignmentId().equals(assignment.id())).findFirst().orElseThrow();
    }

    private AccessReviewService.ReviewItem decide(AccessReviewItem item, AccessReviewItem.Decision decision,
                                                  String reason) {
        return service.decide(item.id(), decision, reason, REVIEWER, reviewer(Scopes.NATIONAL));
    }

    // ------------------------------------------------------------------ opening a review

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: a review snapshots ACTIVE DIRECT assignments within its scope, upcoming ones included")
    void openReviewSnapshotsActiveDirectAssignmentsInScope() {
        RoleAssignment inScope = assign("u-1", scopes("R1"));
        RoleAssignment upcoming = assign("u-2", scopes("R1"), NOW.plusSeconds(86_400), null, ADMIN);
        RoleAssignment revoked = assign("u-3", scopes("R1"));
        iam.revoke(revoked.id(), "moved", ADMIN, admin(Scopes.NATIONAL));
        RoleAssignment ending = assign("u-4", scopes("R1"), null, NOW.plusSeconds(3600), ADMIN);
        RoleAssignment otherRegion = assign("u-5", scopes("R2"));
        RoleAssignment national = assign("u-6", AssignmentScopes.national());
        RoleAssignment twoRegions = assign("u-8", scopes("R1", "R2"));
        RoleAssignment delegation = iam.delegate(inScope.id(), "u-7", null, null, NOW.plusSeconds(86_400), "cover",
                new Actor("u-1", null), grants("u-1"));
        now.set(NOW.plusSeconds(7200)); // `ending` has ended
        audit.entries.clear();

        AccessReviewService.Review review = open("R1");

        List<AccessReviewItem> items = reviews.searchItems(review.campaign().id(), null, null);
        assertThat(items).extracting(AccessReviewItem::assignmentId)
                .containsExactlyInAnyOrder(inScope.id(), upcoming.id())
                .doesNotContain(revoked.id(), ending.id(), otherRegion.id(), national.id(), twoRegions.id(),
                        delegation.id());
        AccessReviewItem snapshot = itemFor(review, inScope);
        assertThat(snapshot.status()).isEqualTo(AccessReviewItem.Status.PENDING);
        assertThat(snapshot.subjectId()).isEqualTo("u-1");
        assertThat(snapshot.subjectUsername()).isEqualTo("u-1.name");
        assertThat(snapshot.roleCode()).isEqualTo("REFDATA_MAKER");
        assertThat(snapshot.scopes()).isEqualTo(scopes("R1"));
        assertThat(snapshot.assignedBy()).isEqualTo(ADMIN);
        assertThat(snapshot.validFrom()).isEqualTo(inScope.validFrom());
        assertThat(snapshot.campaignDueAt()).isEqualTo(review.campaign().dueAt());
        assertThat(review.campaign().status()).isEqualTo(AccessReviewCampaign.Status.OPEN);
        assertThat(review.counts()).isEqualTo(new AccessReviewService.Counts(2, 2, 0, 0, 0, 0, 0));
        assertThat(review.skippedUnderReview()).isZero();
        assertThat(review.overdue()).isFalse();

        // Only a national review reviews national assignments; R1 items already under review are skipped.
        AccessReviewService.Review all = open("*");
        assertThat(reviews.searchItems(all.campaign().id(), null, null)).extracting(AccessReviewItem::assignmentId)
                .containsExactlyInAnyOrder(otherRegion.id(), national.id(), twoRegions.id());
        assertThat(all.skippedUnderReview()).isEqualTo(2);
        assertThat(audit.actions()).containsExactly("IAM_ACCESS_REVIEW_OPENED", "IAM_ACCESS_REVIEW_OPENED");
        assertThat(audit.entries.getFirst().newValue().toString()).contains("itemCount=2", "skippedUnderReview=0");
    }

    @Test
    @Tag("SEC-007")
    @Tag("CC-001")
    @DisplayName("SEC-007/CC-001: opening a review needs iam.review.manage covering its scopes; nothing is created otherwise")
    void openReviewNeedsManageCoveringScope() {
        assign("u-1", scopes("R2"));
        audit.entries.clear();
        Instant due = NOW.plusSeconds(86_400);

        assertThatThrownBy(() -> service.open("Review R2", scopes("R2"), due, "quarterly", ADMIN, admin(regions("R1"))))
                .satisfies(code("SCOPE_EXCEEDED"))
                .satisfies(t -> assertThat(((DomainException) t).kind()).isEqualTo(DomainException.Kind.OUT_OF_SCOPE));
        assertThatThrownBy(() -> service.open("Review all", AssignmentScopes.national(), due, "quarterly", ADMIN,
                admin(regions("R1")))).satisfies(code("SCOPE_EXCEEDED"));
        assertThatThrownBy(() -> service.open("Review R2", scopes("R2"), due, "quarterly", REVIEWER,
                reviewer(Scopes.NATIONAL))).as("reviewers do not open reviews").satisfies(code("SCOPE_EXCEEDED"));
        assertThatThrownBy(() -> service.open("Review R2", scopes("R2"), NOW, "quarterly", ADMIN,
                admin(Scopes.NATIONAL))).satisfies(code("ACCESS_REVIEW_DUE_IN_PAST"));
        assertThat(reviews.campaigns).isEmpty();
        assertThat(reviews.items).isEmpty();
        assertThat(audit.entries).isEmpty();

        assertThat(service.open("Review R1", scopes("R1"), due, "quarterly", ADMIN, admin(regions("R1"))).counts()
                .total()).as("an empty review is allowed and stands as evidence").isZero();
    }

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: an assignment under review is not added to a second review until the first is closed")
    void assignmentUnderReviewIsNotAddedTwice() {
        RoleAssignment a = assign("u-1", scopes("R1"));
        AccessReviewService.Review first = open("R1");
        AccessReviewService.Review second = open("R1");

        assertThat(first.counts().total()).isEqualTo(1);
        assertThat(second.counts().total()).isZero();
        assertThat(second.skippedUnderReview()).isEqualTo(1);

        service.close(first.campaign().id(), "quarter done", ADMIN, admin(Scopes.NATIONAL));
        AccessReviewService.Review third = open("R1");
        assertThat(third.counts().total()).isEqualTo(1);
        assertThat(itemFor(third, a).status()).isEqualTo(AccessReviewItem.Status.PENDING);
    }

    // ------------------------------------------------------------------ decisions

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: a KEEP decision is recorded, audited and leaves the access in place")
    void keepDecisionRecordedAndAudited() {
        RoleAssignment a = assign("u-1", scopes("R1"));
        AccessReviewService.Review review = open("R1");
        AccessReviewItem item = itemFor(review, a);
        audit.entries.clear();
        reviews.locks.clear();
        assignments.locked.clear();

        AccessReviewService.ReviewItem kept = decide(item, AccessReviewItem.Decision.KEEP, null);

        assertThat(kept.item().status()).isEqualTo(AccessReviewItem.Status.KEPT);
        assertThat(kept.item().decidedBy()).isEqualTo(REVIEWER);
        assertThat(kept.item().decidedAt()).isEqualTo(NOW);
        assertThat(kept.item().decisionReason()).isNull();
        assertThat(kept.item().decidedLate()).isFalse();
        assertThat(kept.overdue()).isFalse();
        assertThat(assignments.rows.get(a.id()).status()).isEqualTo(RoleAssignment.Status.ACTIVE);
        assertThat(iam.effectiveAuthorisation("u-1").roles()).containsExactly("REFDATA_MAKER");
        assertThat(service.review(review.campaign().id(), admin(Scopes.NATIONAL)).counts().kept()).isEqualTo(1);
        assertThat(reviews.locks).containsExactly("share " + review.campaign().id(), "item " + item.id());
        assertThat(assignments.locked).containsExactly("u-1");
        assertThat(audit.actions()).containsExactly("IAM_ACCESS_REVIEW_DECIDED");
        assertThat(audit.entries.getFirst().entityId()).isEqualTo(item.id().toString());
        assertThat(service.items(review.campaign().id(), null, null, reviewer(Scopes.NATIONAL)))
                .as("the queue defaults to PENDING").isEmpty();
        assertThat(service.items(review.campaign().id(), AccessReviewItem.Status.KEPT, "u-1",
                reviewer(Scopes.NATIONAL))).hasSize(1);
    }

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: a REVOKE decision revokes the assignment at once; its grant and delegations stop")
    void revokeDecisionRevokesAssignmentAndGrant() {
        RoleAssignment a = assign("u-1", scopes("R1"));
        RoleAssignment delegation = iam.delegate(a.id(), "u-7", null, null, NOW.plusSeconds(86_400), "cover",
                new Actor("u-1", null), grants("u-1"));
        AccessReviewService.Review review = open("R1");
        assertThat(iam.effectiveAuthorisation("u-7").roles()).containsExactly("REFDATA_MAKER");

        AccessReviewService.ReviewItem revoked = decide(itemFor(review, a), AccessReviewItem.Decision.REVOKE,
                "no longer in the unit");

        assertThat(revoked.item().status()).isEqualTo(AccessReviewItem.Status.REVOKED);
        assertThat(revoked.item().decisionReason()).isEqualTo("no longer in the unit");
        RoleAssignment after = assignments.rows.get(a.id());
        assertThat(after.status()).isEqualTo(RoleAssignment.Status.REVOKED);
        assertThat(after.revokedBy()).isEqualTo(REVIEWER);
        assertThat(after.revocationReason()).isEqualTo("no longer in the unit");
        assertThat(iam.effectiveAuthorisation("u-1").grants()).isEmpty();
        assertThat(iam.effectiveAuthorisation("u-7").grants()).as("the delegation is inert").isEmpty();
        assertThat(assignments.rows.get(delegation.id()).status()).isEqualTo(RoleAssignment.Status.ACTIVE);
        assertThat(service.review(review.campaign().id(), admin(Scopes.NATIONAL)).counts().revoked()).isEqualTo(1);
    }

    @Test
    @Tag("SEC-007")
    @Tag("CC-001")
    @DisplayName("SEC-007/CC-001: deciding needs iam.review.decide covering the item; items outside read scope are not found")
    void decideNeedsDecideCoveringItemScope() {
        RoleAssignment a = assign("u-1", scopes("R1"));
        AccessReviewService.Review review = open("*");
        AccessReviewItem item = itemFor(review, a);
        Authorisation readOnly = grants("u-auditor", new Authorisation.Grant("AUDITOR", Set.of("iam.review.read"),
                Scopes.NATIONAL, null));
        Authorisation decideElsewhere = grants(REVIEWER.id(),
                new Authorisation.Grant("AUDITOR", Set.of("iam.review.read"), Scopes.NATIONAL, null),
                new Authorisation.Grant("ACCESS_REVIEWER", REVIEWER_PERMISSIONS, regions("R2"), null));
        UUID id = item.id();

        assertThatThrownBy(() -> service.decide(id, AccessReviewItem.Decision.KEEP, null, new Actor("u-auditor", null),
                readOnly)).satisfies(code("SCOPE_EXCEEDED"));
        assertThatThrownBy(() -> service.decide(id, AccessReviewItem.Decision.KEEP, null, REVIEWER, decideElsewhere))
                .satisfies(code("SCOPE_EXCEEDED"));
        assertThatThrownBy(() -> service.decide(id, AccessReviewItem.Decision.KEEP, null, REVIEWER,
                reviewer(regions("R2")))).satisfies(code("ACCESS_REVIEW_ITEM_NOT_FOUND"));
        assertThatThrownBy(() -> service.decide(UUID.randomUUID(), AccessReviewItem.Decision.KEEP, null, REVIEWER,
                reviewer(Scopes.NATIONAL))).satisfies(code("ACCESS_REVIEW_ITEM_NOT_FOUND"));
        assertThat(item.status()).isEqualTo(AccessReviewItem.Status.PENDING);

        // A regional reviewer works the R1 items of a national review without seeing the national header.
        Authorisation r1Reviewer = reviewer(regions("R1"));
        assertThat(service.items(review.campaign().id(), null, null, r1Reviewer)).extracting(i -> i.item().id())
                .containsExactly(id);
        assertThat(service.items(review.campaign().id(), null, null, reviewer(regions("R2")))).isEmpty();
        assertThat(service.reviews(null, r1Reviewer)).isEmpty();
        UUID campaignId = review.campaign().id();
        assertThatThrownBy(() -> service.review(campaignId, r1Reviewer)).satisfies(code("ACCESS_REVIEW_NOT_FOUND"));
        assertThat(service.decide(id, AccessReviewItem.Decision.KEEP, null, REVIEWER, r1Reviewer).item().status())
                .isEqualTo(AccessReviewItem.Status.KEPT);
        assertThat(service.reviews(AccessReviewCampaign.Status.OPEN, reviewer(Scopes.NATIONAL))).hasSize(1);
        assertThat(service.reviews(AccessReviewCampaign.Status.CLOSED, reviewer(Scopes.NATIONAL))).isEmpty();
    }

    @Test
    @Tag("SEC-007")
    @Tag("CC-002")
    @DisplayName("SEC-007/CC-002: nobody reviews their own access or an assignment they granted")
    void noSelfReviewNoReviewOfOwnGrant() {
        RoleAssignment own = assign(REVIEWER.id(), scopes("R1"));
        RoleAssignment grantedByReviewer = assign("u-2", scopes("R1"), null, null, REVIEWER);
        AccessReviewService.Review review = open("R1");
        audit.entries.clear();

        assertThatThrownBy(() -> decide(itemFor(review, own), AccessReviewItem.Decision.KEEP, null))
                .satisfies(code("ACCESS_REVIEW_SELF"))
                .satisfies(t -> assertThat(((DomainException) t).requirementRefs()).contains("CC-002"));
        assertThatThrownBy(() -> decide(itemFor(review, grantedByReviewer), AccessReviewItem.Decision.REVOKE,
                "granted it myself")).satisfies(code("ACCESS_REVIEW_OWN_GRANT"));
        assertThat(reviews.items.values()).extracting(AccessReviewItem::status)
                .containsOnly(AccessReviewItem.Status.PENDING);
        assertThat(assignments.rows.get(grantedByReviewer.id()).status()).isEqualTo(RoleAssignment.Status.ACTIVE);
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: an assignment revoked by a leaver deactivation cannot be decided; closure records it ENDED")
    void decisionOnEndedAssignmentRefused() {
        RoleAssignment a = assign("u-leaver", scopes("R1"));
        AccessReviewService.Review review = open("R1");
        iam.deactivateSubject("u-leaver", "left the Authority", FEED, grants(FEED.id(), new Authorisation.Grant(
                "DIRECTORY_FEED", Set.of("iam.subject.deactivate"), Scopes.NATIONAL, null)));

        assertThatThrownBy(() -> decide(itemFor(review, a), AccessReviewItem.Decision.KEEP, null))
                .satisfies(code("ACCESS_REVIEW_ASSIGNMENT_ENDED"))
                .satisfies(t -> assertThat(((DomainException) t).kind()).isEqualTo(DomainException.Kind.CONFLICT));
        assertThatThrownBy(() -> decide(itemFor(review, a), AccessReviewItem.Decision.REVOKE, "already gone"))
                .satisfies(code("ACCESS_REVIEW_ASSIGNMENT_ENDED"));

        AccessReviewService.Review closed = service.close(review.campaign().id(), "quarter done", ADMIN,
                admin(Scopes.NATIONAL));
        assertThat(itemFor(review, a).status()).isEqualTo(AccessReviewItem.Status.ENDED);
        assertThat(closed.counts()).isEqualTo(new AccessReviewService.Counts(1, 0, 0, 0, 0, 0, 1));
    }

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: undecided items past the due date are flagged overdue and never revoked automatically")
    void overdueItemsAreNeverRevoked() {
        RoleAssignment a = assign("u-1", scopes("R1"));
        RoleAssignment b = assign("u-2", scopes("R1"));
        AccessReviewService.Review review = open("R1");
        now.set(review.campaign().dueAt().plusSeconds(86_400));

        AccessReviewService.Review later = service.review(review.campaign().id(), admin(Scopes.NATIONAL));
        assertThat(later.overdue()).isTrue();
        assertThat(later.counts().overdue()).isEqualTo(2);
        assertThat(later.counts().pending()).isEqualTo(2);
        assertThat(service.items(review.campaign().id(), null, null, reviewer(Scopes.NATIONAL)))
                .allSatisfy(i -> assertThat(i.overdue()).isTrue()).hasSize(2);
        assertThat(assignments.rows.get(a.id()).status()).isEqualTo(RoleAssignment.Status.ACTIVE);
        assertThat(assignments.rows.get(b.id()).status()).isEqualTo(RoleAssignment.Status.ACTIVE);
        assertThat(audit.actions()).doesNotContain("IAM_ROLE_REVOKED");

        // A late decision is still accepted while the review is open, and is marked.
        AccessReviewService.ReviewItem late = decide(itemFor(review, a), AccessReviewItem.Decision.KEEP, null);
        assertThat(late.item().decidedLate()).isTrue();
        assertThat(late.overdue()).isFalse();
    }

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: closing records undecided items as NOT_REVIEWED, revokes nothing and happens once")
    void closureMarksNotReviewedAndRevokesNothing() {
        RoleAssignment keep = assign("u-1", scopes("R1"));
        RoleAssignment revoke = assign("u-2", scopes("R1"));
        RoleAssignment undecided = assign("u-3", scopes("R1"));
        AccessReviewService.Review review = open("R1");
        UUID id = review.campaign().id();
        decide(itemFor(review, keep), AccessReviewItem.Decision.KEEP, null);
        decide(itemFor(review, revoke), AccessReviewItem.Decision.REVOKE, "not needed");

        assertThatThrownBy(() -> service.close(id, "done", REVIEWER, reviewer(Scopes.NATIONAL)))
                .as("reviewers do not close reviews").satisfies(code("SCOPE_EXCEEDED"));
        assertThatThrownBy(() -> service.close(id, "done", ADMIN, grants(ADMIN.id(), new Authorisation.Grant(
                "IT_ADMINISTRATOR", ADMIN_PERMISSIONS, regions("R2"), null)))).satisfies(code("ACCESS_REVIEW_NOT_FOUND"));
        assertThatThrownBy(() -> service.close(UUID.randomUUID(), "done", ADMIN, admin(Scopes.NATIONAL)))
                .satisfies(code("ACCESS_REVIEW_NOT_FOUND"));
        assertThatThrownBy(() -> service.close(id, "x", ADMIN, admin(Scopes.NATIONAL)))
                .satisfies(code("REASON_REQUIRED"));
        reviews.locks.clear();

        AccessReviewService.Review closed = service.close(id, "quarter done", ADMIN, admin(Scopes.NATIONAL));

        assertThat(reviews.locks).containsExactly("update " + id);
        assertThat(closed.campaign().status()).isEqualTo(AccessReviewCampaign.Status.CLOSED);
        assertThat(closed.campaign().closedBy()).isEqualTo(ADMIN);
        assertThat(closed.campaign().closureReason()).isEqualTo("quarter done");
        assertThat(closed.overdue()).isFalse();
        assertThat(closed.counts()).isEqualTo(new AccessReviewService.Counts(3, 0, 0, 1, 1, 1, 0));
        AccessReviewItem notReviewed = itemFor(review, undecided);
        assertThat(notReviewed.status()).isEqualTo(AccessReviewItem.Status.NOT_REVIEWED);
        assertThat(assignments.rows.get(undecided.id()).status()).isEqualTo(RoleAssignment.Status.ACTIVE);
        assertThat(assignments.rows.get(keep.id()).status()).isEqualTo(RoleAssignment.Status.ACTIVE);

        assertThatThrownBy(() -> service.close(id, "again", ADMIN, admin(Scopes.NATIONAL)))
                .satisfies(code("ACCESS_REVIEW_CLOSED"));
        assertThatThrownBy(() -> decide(notReviewed, AccessReviewItem.Decision.KEEP, null))
                .as("decisionAfterClosureRefused").satisfies(code("ACCESS_REVIEW_CLOSED"));
        assertThat(service.reviews(AccessReviewCampaign.Status.CLOSED, admin(Scopes.NATIONAL)))
                .extracting(r -> r.campaign().id()).containsExactly(id);
    }

    // ------------------------------------------------------------------ overdue listing (configurable period)

    /** Old (granted 4 months ago, never reviewed), kept (granted 4 months ago, kept 40 days ago), recent (2 months). */
    private List<RoleAssignment> overdueFixture() {
        now.set(NOW.atZone(ZoneOffset.UTC).minusMonths(4).toInstant());
        RoleAssignment old = assign("u-old", scopes("R1"));
        RoleAssignment kept = assign("u-kept", scopes("R1"));
        now.set(NOW.atZone(ZoneOffset.UTC).minusMonths(2).toInstant());
        RoleAssignment recent = assign("u-recent", scopes("R1"));
        now.set(NOW.minusSeconds(40L * 86_400));
        AccessReviewService.Review review = open("R1");
        decide(itemFor(review, kept), AccessReviewItem.Decision.KEEP, "still in the unit");
        now.set(NOW);
        return List.of(old, kept, recent);
    }

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: with the default quarterly period, access not kept by a review in 3 months is overdue")
    void overdueAssignmentsDefaultPeriod() {
        List<RoleAssignment> fixture = overdueFixture();
        RoleAssignment old = fixture.get(0);
        UUID pendingItem = reviews.pendingItemIds(List.of(old.id())).get(old.id());

        AccessReviewService.OverdueAssignments result = service.overdueAssignments(reviewer(Scopes.NATIONAL));

        assertThat(result.reviewPeriod()).isEqualTo(Period.ofMonths(3));
        assertThat(result.evaluatedAt()).isEqualTo(NOW);
        assertThat(result.assignments()).singleElement().satisfies(o -> {
            assertThat(o.assignment().id()).isEqualTo(old.id());
            assertThat(o.lastReviewedAt()).isNull();
            assertThat(o.reviewDueSince()).isEqualTo(old.assignedAt().atZone(ZoneOffset.UTC).plusMonths(3)
                    .toInstant());
            assertThat(o.pendingItemId()).isEqualTo(pendingItem);
        });
        assertThat(service.overdueAssignments(reviewer(regions("R2"))).assignments()).as("scope-filtered").isEmpty();
        assertThat(assignments.rows.get(old.id()).status()).as("nothing revoked")
                .isEqualTo(RoleAssignment.Status.ACTIVE);
    }

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: the review period is configuration: P1M changes the result for the same data")
    void overdueAssignmentsConfiguredPeriod() {
        List<RoleAssignment> fixture = overdueFixture();
        AccessReviewService monthly = new AccessReviewService(reviews, assignments, audit,
                AccessReviewPolicy.parse("P1M"), clock);

        AccessReviewService.OverdueAssignments result = monthly.overdueAssignments(reviewer(Scopes.NATIONAL));

        assertThat(result.reviewPeriod()).isEqualTo(Period.ofMonths(1));
        assertThat(result.assignments()).extracting(o -> o.assignment().id())
                .containsExactlyInAnyOrderElementsOf(fixture.stream().map(RoleAssignment::id).toList());
        assertThat(result.assignments()).filteredOn(o -> o.assignment().subjectId().equals("u-kept"))
                .singleElement().satisfies(o -> {
                    assertThat(o.lastReviewedAt()).isEqualTo(NOW.minusSeconds(40L * 86_400));
                    assertThat(o.reviewDueSince()).isEqualTo(NOW.minusSeconds(40L * 86_400).atZone(ZoneOffset.UTC)
                            .plusMonths(1).toInstant());
                });
    }

    // ------------------------------------------------------------------ audit

    @Test
    @Tag("CC-120")
    @Tag("SEC-007")
    @DisplayName("CC-120: opening, deciding, revoking and closing are audited with old and new values")
    void accessReviewAuditActions() {
        RoleAssignment keep = assign("u-1", scopes("R1"));
        RoleAssignment revoke = assign("u-2", scopes("R1"));
        assign("u-3", scopes("R1"));
        audit.entries.clear();

        AccessReviewService.Review review = open("R1");
        AccessReviewItem kept = itemFor(review, keep);
        decide(kept, AccessReviewItem.Decision.KEEP, null);
        decide(itemFor(review, revoke), AccessReviewItem.Decision.REVOKE, "not needed");
        service.close(review.campaign().id(), "quarter done", ADMIN, admin(Scopes.NATIONAL));

        assertThat(audit.actions()).containsExactly("IAM_ACCESS_REVIEW_OPENED", "IAM_ACCESS_REVIEW_DECIDED",
                "IAM_ROLE_REVOKED", "IAM_ACCESS_REVIEW_DECIDED", "IAM_ACCESS_REVIEW_CLOSED");
        InMemoryRepositories.AuditEntry opened = audit.entries.get(0);
        assertThat(opened.entityType()).isEqualTo("AccessReview");
        assertThat(opened.oldValue()).isNull();
        assertThat(opened.reason()).isEqualTo("quarterly access review");
        InMemoryRepositories.AuditEntry decided = audit.entries.get(1);
        assertThat(decided.entityType()).isEqualTo("AccessReviewItem");
        assertThat(decided.entityId()).isEqualTo(kept.id().toString());
        assertThat(decided.oldValue().toString()).contains("status=PENDING");
        assertThat(decided.newValue().toString()).contains("status=KEPT", "decidedBy=u-reviewer");
        InMemoryRepositories.AuditEntry roleRevoked = audit.entries.get(2);
        assertThat(roleRevoked.entityId()).isEqualTo(revoke.id().toString());
        assertThat(roleRevoked.oldValue().toString()).contains("status=ACTIVE");
        assertThat(roleRevoked.newValue().toString()).contains("status=REVOKED", "revokedBy=u-reviewer");
        assertThat(roleRevoked.reason()).isEqualTo("not needed");
        assertThat(audit.entries.get(3).reason()).isEqualTo("not needed");
        InMemoryRepositories.AuditEntry closed = audit.entries.get(4);
        assertThat(closed.oldValue().toString()).contains("status=OPEN");
        assertThat(closed.newValue().toString()).contains("status=CLOSED", "notReviewed=1", "notReviewedItemIds=[");
        assertThat(closed.reason()).isEqualTo("quarter done");
    }
}

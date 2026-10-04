package gov.itms.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.iam.domain.Actor;
import gov.itms.iam.domain.ApprovalLimit;
import gov.itms.iam.domain.AssignmentScopes;
import gov.itms.iam.domain.BilingualName;
import gov.itms.iam.domain.DomainException;
import gov.itms.iam.domain.Permission;
import gov.itms.iam.domain.Role;
import gov.itms.iam.domain.RoleAssignment;
import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.ScopeTarget;
import gov.itms.platform.security.Scopes;
import java.time.Clock;
import java.time.Instant;
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

class IamServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
    private static final Actor ADMIN = new Actor("u-admin", "admin");
    private static final BilingualName NAME = new BilingualName("دور", "Role");
    private static final Set<String> IAM_PERMISSIONS = Set.of("iam.role.read", "iam.role.manage",
            "iam.assignment.read", "iam.assignment.manage");

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private InMemoryRepositories.Permissions permissions;
    private InMemoryRepositories.Roles roles;
    private InMemoryRepositories.Assignments assignments;
    private InMemoryRepositories.Audit audit;
    private InMemoryRepositories.ApprovalLimits limits;
    private IamService service;

    @BeforeEach
    void setUp() {
        permissions = new InMemoryRepositories.Permissions();
        roles = new InMemoryRepositories.Roles();
        assignments = new InMemoryRepositories.Assignments();
        audit = new InMemoryRepositories.Audit();
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        limits = new InMemoryRepositories.ApprovalLimits();
        service = new IamService(permissions, roles, assignments, limits, audit, clock);
        for (String p : List.of("iam.role.read", "iam.role.manage", "iam.assignment.read", "iam.assignment.manage",
                "refdata.read", "refdata.change.submit", "txn.create")) {
            permissions.rows.put(p, new Permission(p, "svc", NAME));
        }
        roles.insert(Role.create("IT_ADMINISTRATOR", NAME, null, IAM_PERMISSIONS), "seed", NOW);
        roles.insert(Role.create("REFDATA_MAKER", NAME, null, List.of("refdata.read", "refdata.change.submit")),
                "seed", NOW);
    }

    private static Consumer<Throwable> code(String expected) {
        return t -> assertThat(((DomainException) t).code()).isEqualTo(expected);
    }

    /** An administrator whose grants carry the given scopes. */
    private static Authorisation admin(Scopes scopes, String... roleCodes) {
        List<Authorisation.Grant> grants = roleCodes.length == 0
                ? List.of(new Authorisation.Grant("IT_ADMINISTRATOR", IAM_PERMISSIONS, scopes, null))
                : List.of(roleCodes).stream()
                        .map(r -> new Authorisation.Grant(r, IAM_PERMISSIONS, scopes, null)).toList();
        return new Authorisation("u-admin", grants, NOW);
    }

    private static Scopes regions(String... regions) {
        return new Scopes(Set.of(regions), Set.of("*"), Set.of("*"));
    }

    private static AssignmentScopes assignmentScopes(String... regions) {
        return AssignmentScopes.of(List.of(regions), List.of("*"), List.of("*"));
    }

    private RoleAssignment assign(String subject, AssignmentScopes scopes, Authorisation caller) {
        return service.assign(subject, subject + ".name", "REFDATA_MAKER", scopes, null, null, "new joiner", ADMIN,
                caller);
    }

    // ------------------------------------------------------------------ roles (CC-005)

    @Test
    @Tag("CC-005")
    @DisplayName("CC-005: an administrator creates a role and changes its permissions without code; both are audited")
    void createAndRedefineRole() {
        Role created = service.createRole("CENTRE_AGENT", NAME, "Front counter", List.of("txn.create"),
                "new BRD role", ADMIN);
        Role updated = service.updateRole("CENTRE_AGENT", NAME, "Front counter", true,
                List.of("txn.create", "refdata.read"), 1, "agents need reference data", ADMIN,
                admin(Scopes.NATIONAL));

        assertThat(created.version()).isEqualTo(1);
        assertThat(updated.version()).isEqualTo(2);
        assertThat(service.role("CENTRE_AGENT").permissions()).containsExactly("refdata.read", "txn.create");
        assertThat(service.roleHistory("CENTRE_AGENT")).hasSize(2)
                .first().satisfies(v -> assertThat(v.validTo()).isEqualTo(NOW));
        assertThat(audit.actions()).containsExactly("IAM_ROLE_CREATED", "IAM_ROLE_UPDATED");
        assertThat(audit.entries.get(1).oldValue()).isEqualTo(created);
        assertThat(audit.entries.get(1).reason()).isEqualTo("agents need reference data");
        assertThat(service.roles()).extracting(Role::code)
                .containsExactly("CENTRE_AGENT", "IT_ADMINISTRATOR", "REFDATA_MAKER");
        assertThat(service.permissions()).hasSize(7);
    }

    @Test
    @Tag("CC-005")
    @DisplayName("CC-005: roles may only contain catalogue permissions; duplicates and stale versions are refused")
    void roleRules() {
        assertThatThrownBy(() -> service.createRole("NEW_ROLE", NAME, null, List.of("made.up"), "reason", ADMIN))
                .satisfies(code("UNKNOWN_PERMISSION"));
        assertThatThrownBy(() -> service.createRole("REFDATA_MAKER", NAME, null, List.of(), "reason", ADMIN))
                .satisfies(code("ROLE_EXISTS"));
        assertThatThrownBy(() -> service.updateRole("REFDATA_MAKER", NAME, null, true, List.of(), 7, "reason", ADMIN,
                admin(Scopes.NATIONAL))).satisfies(code("STALE_ROLE"));
        assertThatThrownBy(() -> service.role("NO_SUCH_ROLE")).satisfies(code("ROLE_NOT_FOUND"));
        assertThatThrownBy(() -> service.roleHistory("NO_SUCH_ROLE")).satisfies(code("ROLE_NOT_FOUND"));
        assertThatThrownBy(() -> service.createRole("NEW_ROLE", NAME, null, List.of(), "x", ADMIN))
                .satisfies(code("REASON_REQUIRED"));
    }

    @Test
    @Tag("CC-002")
    @DisplayName("CC-002: an administrator cannot change a role they hold (no self-widening of rights)")
    void cannotChangeOwnRole() {
        assertThatThrownBy(() -> service.updateRole("IT_ADMINISTRATOR", NAME, null, true,
                List.of("iam.role.read", "refdata.read"), 1, "widen my rights", ADMIN, admin(Scopes.NATIONAL)))
                .satisfies(code("ROLE_HELD_BY_ACTOR"))
                .satisfies(t -> assertThat(((DomainException) t).requirementRefs()).contains("CC-002"));
        assertThat(audit.entries).isEmpty();
    }

    // ------------------------------------------------------------------ assignments and data scopes (CC-001)

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: assignments carry data scopes; the effective authorisation keeps them per grant")
    void effectiveAuthorisationKeepsScopes() {
        assign("u-1", assignmentScopes("R1"), admin(Scopes.NATIONAL));

        Authorisation a = service.effectiveAuthorisation("u-1");
        assertThat(a.roles()).containsExactly("REFDATA_MAKER");
        assertThat(a.permissions()).containsExactly("refdata.change.submit", "refdata.read");
        assertThat(a.allows("refdata.read", ScopeTarget.region("R1"))).isTrue();
        assertThat(a.allows("refdata.read", ScopeTarget.region("R2"))).isFalse();
        assertThat(audit.actions()).containsExactly("IAM_ROLE_ASSIGNED");
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: future, expired, revoked assignments and inactive roles grant nothing")
    void onlyEffectiveGrantsCount() {
        service.assign("u-1", null, "REFDATA_MAKER", assignmentScopes("R1"), NOW.plusSeconds(3600), null,
                "starts later", ADMIN, admin(Scopes.NATIONAL));
        RoleAssignment temporary = service.assign("u-2", null, "REFDATA_MAKER", assignmentScopes("R1"), null,
                NOW.plusSeconds(60), "short cover", ADMIN, admin(Scopes.NATIONAL));
        RoleAssignment revoked = assign("u-3", assignmentScopes("R1"), admin(Scopes.NATIONAL));
        service.revoke(revoked.id(), "left the unit", ADMIN, admin(Scopes.NATIONAL));

        assertThat(service.effectiveAuthorisation("u-1").grants()).isEmpty();
        assertThat(service.effectiveAuthorisation("u-2").grants()).singleElement()
                .satisfies(g -> assertThat(g.validTo()).isEqualTo(temporary.validTo()));
        assertThat(service.effectiveAuthorisation("u-3").grants()).isEmpty();

        now.set(NOW.plusSeconds(3600));
        assertThat(service.effectiveAuthorisation("u-1").grants()).hasSize(1);
        assertThat(service.effectiveAuthorisation("u-2").grants()).as("expired").isEmpty();

        service.updateRole("REFDATA_MAKER", NAME, null, false, List.of("refdata.read"), 1, "retire role", ADMIN,
                admin(Scopes.NATIONAL));
        assertThat(service.effectiveAuthorisation("u-1").grants()).as("inactive role").isEmpty();
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: an administrator cannot grant beyond their own regions")
    void cannotGrantBeyondOwnScope() {
        Authorisation regionalAdmin = admin(regions("R1"));

        assertThat(assign("u-1", assignmentScopes("R1"), regionalAdmin).scopes().regions()).containsExactly("R1");
        assertThatThrownBy(() -> assign("u-2", assignmentScopes("R2"), regionalAdmin))
                .satisfies(code("SCOPE_EXCEEDED"))
                .satisfies(t -> assertThat(((DomainException) t).kind()).isEqualTo(DomainException.Kind.OUT_OF_SCOPE));
        assertThatThrownBy(() -> assign("u-3", AssignmentScopes.national(), regionalAdmin))
                .satisfies(code("SCOPE_EXCEEDED"));
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: assignments outside the caller's scope are invisible and cannot be revoked")
    void outOfScopeAssignmentsAreInvisible() {
        RoleAssignment inR1 = assign("u-1", assignmentScopes("R1"), admin(Scopes.NATIONAL));
        RoleAssignment inR2 = assign("u-2", assignmentScopes("R2"), admin(Scopes.NATIONAL));
        Authorisation r1Admin = admin(regions("R1"));

        assertThat(service.assignments(null, null, null, r1Admin)).extracting(RoleAssignment::id)
                .containsExactly(inR1.id());
        assertThat(service.assignments(null, null, null, admin(Scopes.NATIONAL))).hasSize(2);
        assertThat(service.assignment(inR1.id(), r1Admin).subjectId()).isEqualTo("u-1");
        assertThatThrownBy(() -> service.assignment(inR2.id(), r1Admin)).satisfies(code("ASSIGNMENT_NOT_FOUND"));
        assertThatThrownBy(() -> service.revoke(inR2.id(), "not mine", ADMIN, r1Admin))
                .satisfies(code("ASSIGNMENT_NOT_FOUND"));

        // Can read (national read) but cannot manage outside R1: refused as out of scope.
        Authorisation readAllManageR1 = new Authorisation("u-admin", List.of(
                new Authorisation.Grant("AUDITOR", Set.of("iam.assignment.read"), Scopes.NATIONAL, null),
                new Authorisation.Grant("IT_ADMINISTRATOR", Set.of("iam.assignment.manage"), regions("R1"), null)),
                NOW);
        assertThatThrownBy(() -> service.revoke(inR2.id(), "not mine", ADMIN, readAllManageR1))
                .satisfies(code("SCOPE_EXCEEDED"));
        assertThat(service.revoke(inR1.id(), "moved region", ADMIN, readAllManageR1).status())
                .isEqualTo(RoleAssignment.Status.REVOKED);
    }

    @Test
    @Tag("CC-002")
    @DisplayName("CC-002: self-assignment is refused before anything is stored")
    void selfAssignmentRefused() {
        assertThatThrownBy(() -> assign("u-admin", assignmentScopes("R1"), admin(Scopes.NATIONAL)))
                .satisfies(code("SELF_ASSIGNMENT"));
        assertThat(assignments.rows).isEmpty();
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: duplicate overlapping assignments and inactive or unknown roles are refused")
    void assignmentConflicts() {
        assign("u-1", assignmentScopes("R1"), admin(Scopes.NATIONAL));
        assertThatThrownBy(() -> assign("u-1", assignmentScopes("R1"), admin(Scopes.NATIONAL)))
                .satisfies(code("ASSIGNMENT_EXISTS"));
        assertThat(assign("u-1", assignmentScopes("R2"), admin(Scopes.NATIONAL)).status())
                .as("same role in another region is a separate grant").isEqualTo(RoleAssignment.Status.ACTIVE);

        assertThatThrownBy(() -> service.assign("u-9", null, "NO_SUCH_ROLE", assignmentScopes("R1"), null, null,
                "reason", ADMIN, admin(Scopes.NATIONAL))).satisfies(code("ROLE_NOT_FOUND"));
        service.updateRole("REFDATA_MAKER", NAME, null, false, List.of(), 1, "retire", ADMIN, admin(Scopes.NATIONAL));
        assertThatThrownBy(() -> assign("u-9", assignmentScopes("R1"), admin(Scopes.NATIONAL)))
                .satisfies(code("ROLE_INACTIVE"));
        assertThatThrownBy(() -> service.assignment(UUID.randomUUID(), admin(Scopes.NATIONAL)))
                .satisfies(code("ASSIGNMENT_NOT_FOUND"));
    }

    @Test
    @Tag("CC-120")
    @DisplayName("CC-120: revocation is audited with the before and after state and the reason")
    void revocationAudited() {
        RoleAssignment a = assign("u-1", assignmentScopes("R1"), admin(Scopes.NATIONAL));
        service.revoke(a.id(), "left the Authority", ADMIN, admin(Scopes.NATIONAL));

        InMemoryRepositories.AuditEntry entry = audit.entries.get(1);
        assertThat(entry.action()).isEqualTo("IAM_ROLE_REVOKED");
        assertThat(entry.reason()).isEqualTo("left the Authority");
        assertThat(entry.oldValue().toString()).contains("status=ACTIVE");
        assertThat(entry.newValue().toString()).contains("status=REVOKED");
    }

    // ------------------------------------------------------------------ bootstrap

    @Test
    @Tag("SEC-004")
    @DisplayName("SEC-004: bootstrap assigns the first administrator only while nobody can manage assignments")
    void bootstrapOnlyWhenNoAdministrator() {
        assertThat(service.bootstrapAdministrator("u-first", "first.admin")).isTrue();
        assertThat(service.effectiveAuthorisation("u-first").permissions()).contains("iam.assignment.manage");
        assertThat(audit.entries).singleElement()
                .satisfies(e -> assertThat(e.actorId()).isEqualTo("SYSTEM_BOOTSTRAP"));

        assertThat(service.bootstrapAdministrator("u-other", null)).isFalse();
        assertThat(service.effectiveAuthorisation("u-other").grants()).isEmpty();
    }

    // ------------------------------------------------------------------ approval limits (CC-003)

    private static final ApprovalLimit REFUND_LIMIT = new ApprovalLimit("REFUND_AMOUNT", 250_000, "XTS");
    private static final ApprovalLimit FINE_LIMIT = new ApprovalLimit("FINE_REDUCTION_AMOUNT", 5_000, "XTS");

    @Test
    @Tag("CC-003")
    @DisplayName("CC-003: by default a role has no approval limits, so its holders have no authority to approve")
    void noLimitsByDefault() {
        assign("u-1", assignmentScopes("R1"), admin(Scopes.NATIONAL));

        assertThat(service.approvalLimits("REFDATA_MAKER").limits()).isEmpty();
        assertThat(service.effectiveAuthorisation("u-1").approvalLimit("REFUND_AMOUNT", "XTS",
                ScopeTarget.region("R1"))).isEmpty();
    }

    @Test
    @Tag("CC-003")
    @DisplayName("CC-003: configured limits replace the role's set, bump its version, are audited and reach the grants")
    void configuredLimitsReachGrants() {
        assign("u-1", assignmentScopes("R1"), admin(Scopes.NATIONAL));

        IamService.RoleApprovalLimits set = service.replaceApprovalLimits("REFDATA_MAKER",
                List.of(REFUND_LIMIT, FINE_LIMIT), 1, "finance policy 2026", ADMIN, admin(Scopes.NATIONAL));

        assertThat(set.version()).isEqualTo(2);
        assertThat(set.limits()).containsExactly(FINE_LIMIT, REFUND_LIMIT);
        assertThat(service.role("REFDATA_MAKER").version()).isEqualTo(2);
        assertThat(service.roleHistory("REFDATA_MAKER")).hasSize(2);
        Authorisation u1 = service.effectiveAuthorisation("u-1");
        assertThat(u1.approvalLimit("REFUND_AMOUNT", "XTS", ScopeTarget.region("R1"))).hasValue(250_000);
        assertThat(u1.approvalLimit("REFUND_AMOUNT", "XTS", ScopeTarget.region("R2"))).as("outside scope").isEmpty();

        // A different configured value, then removal of one limit: nothing is fixed in code.
        service.replaceApprovalLimits("REFDATA_MAKER", List.of(new ApprovalLimit("REFUND_AMOUNT", 1, "XTS")), 2,
                "tighten refunds", ADMIN, admin(Scopes.NATIONAL));
        Authorisation after = service.effectiveAuthorisation("u-1");
        assertThat(after.approvalLimit("REFUND_AMOUNT", "XTS", ScopeTarget.region("R1"))).hasValue(1);
        assertThat(after.approvalLimit("FINE_REDUCTION_AMOUNT", "XTS", ScopeTarget.region("R1"))).isEmpty();

        assertThat(audit.actions()).containsExactly("IAM_ROLE_ASSIGNED", "IAM_APPROVAL_LIMITS_REPLACED",
                "IAM_APPROVAL_LIMITS_REPLACED");
        InMemoryRepositories.AuditEntry last = audit.entries.get(2);
        assertThat(last.oldValue()).isEqualTo(set);
        assertThat(last.reason()).isEqualTo("tighten refunds");
        assertThat(limits.history).hasSize(2);
    }

    @Test
    @Tag("CC-003")
    @Tag("CC-002")
    @DisplayName("CC-003: limits of a held role, stale versions, duplicates and unknown roles are refused")
    void limitRules() {
        assertThatThrownBy(() -> service.replaceApprovalLimits("IT_ADMINISTRATOR", List.of(REFUND_LIMIT), 1,
                "raise my own limit", ADMIN, admin(Scopes.NATIONAL))).satisfies(code("ROLE_HELD_BY_ACTOR"));
        assertThatThrownBy(() -> service.replaceApprovalLimits("REFDATA_MAKER", List.of(REFUND_LIMIT), 9,
                "stale", ADMIN, admin(Scopes.NATIONAL))).satisfies(code("STALE_ROLE"));
        assertThatThrownBy(() -> service.replaceApprovalLimits("REFDATA_MAKER", List.of(REFUND_LIMIT,
                new ApprovalLimit("REFUND_AMOUNT", 1, "XTS")), 1, "twice", ADMIN, admin(Scopes.NATIONAL)))
                .satisfies(code("LIMIT_DUPLICATE"));
        assertThatThrownBy(() -> service.replaceApprovalLimits("NO_SUCH_ROLE", List.of(), 1, "missing", ADMIN,
                admin(Scopes.NATIONAL))).satisfies(code("ROLE_NOT_FOUND"));
        assertThatThrownBy(() -> service.approvalLimits("NO_SUCH_ROLE")).satisfies(code("ROLE_NOT_FOUND"));
        assertThat(audit.entries).isEmpty();
        assertThat(limits.rows).isEmpty();
    }

    // ------------------------------------------------------------------ delegation (CC-004)

    private static final Actor HOLDER = new Actor("u-holder", "holder");

    /** A caller with no permissions at all: only their identity matters. */
    private static Authorisation plain(String subject) {
        return new Authorisation(subject, List.of(), NOW);
    }

    private static Authorisation delegationAdmin(Scopes scopes) {
        return new Authorisation("u-admin", List.of(new Authorisation.Grant("IT_ADMINISTRATOR",
                Set.of("iam.delegation.create", "iam.delegation.read"), scopes, null)), NOW);
    }

    private RoleAssignment holderAssignment(Instant validTo) {
        return service.assign("u-holder", "holder", "REFDATA_MAKER", assignmentScopes("R1"), null, validTo,
                "new joiner", ADMIN, admin(Scopes.NATIONAL));
    }

    @Test
    @Tag("CC-004")
    @DisplayName("CC-004: the holder delegates for a set period; the delegate gets the grant until it ends; audited")
    void holderDelegates() {
        RoleAssignment source = holderAssignment(null);

        RoleAssignment d = service.delegate(source.id(), "u-deputy", "deputy", null, NOW.plusSeconds(3600),
                "annual leave", HOLDER, plain("u-holder"));

        assertThat(d.delegatedFrom()).isEqualTo(source.id());
        Authorisation deputy = service.effectiveAuthorisation("u-deputy");
        assertThat(deputy.roles()).containsExactly("REFDATA_MAKER");
        assertThat(deputy.allows("refdata.read", ScopeTarget.region("R1"))).isTrue();
        assertThat(deputy.allows("refdata.read", ScopeTarget.region("R2"))).isFalse();
        assertThat(deputy.grants()).singleElement().satisfies(g -> assertThat(g.validTo()).isEqualTo(d.validTo()));
        InMemoryRepositories.AuditEntry entry = audit.entries.get(1);
        assertThat(entry.action()).isEqualTo("IAM_DELEGATION_CREATED");
        assertThat(entry.reason()).isEqualTo("annual leave");
        assertThat(entry.newValue().toString()).contains("source=DELEGATION", "delegatedFrom=" + source.id());

        now.set(NOW.plusSeconds(3600));
        assertThat(service.effectiveAuthorisation("u-deputy").grants()).as("delegation period over").isEmpty();
        assertThat(service.effectiveAuthorisation("u-holder").grants()).as("holder keeps the role").hasSize(1);
    }

    @Test
    @Tag("CC-004")
    @DisplayName("CC-004: a delegate gains nothing once the source is revoked or has expired")
    void delegateLosesGrantWithSource() {
        RoleAssignment revokedSource = holderAssignment(null);
        service.delegate(revokedSource.id(), "u-deputy", null, null, NOW.plusSeconds(7200), "cover", HOLDER,
                plain("u-holder"));
        service.revoke(revokedSource.id(), "holder moved", ADMIN, admin(Scopes.NATIONAL));
        assertThat(service.effectiveAuthorisation("u-deputy").grants()).isEmpty();

        RoleAssignment expiring = service.assign("u-holder2", null, "REFDATA_MAKER", assignmentScopes("R2"), null,
                NOW.plusSeconds(600), "temporary", ADMIN, admin(Scopes.NATIONAL));
        service.delegate(expiring.id(), "u-deputy2", null, null, NOW.plusSeconds(600), "cover",
                new Actor("u-holder2", null), plain("u-holder2"));
        assertThat(service.effectiveAuthorisation("u-deputy2").grants()).hasSize(1);
        now.set(NOW.plusSeconds(600));
        assertThat(service.effectiveAuthorisation("u-deputy2").grants()).isEmpty();
    }

    @Test
    @Tag("CC-004")
    @Tag("CC-002")
    @DisplayName("CC-004: an administrator delegates for an absent holder only within iam.delegation.create scope")
    void administratorDelegatesInScope() {
        RoleAssignment source = holderAssignment(NOW.plusSeconds(86_400));

        RoleAssignment d = service.delegate(source.id(), "u-deputy", null, null, NOW.plusSeconds(3600),
                "holder is ill", ADMIN, delegationAdmin(regions("R1")));
        assertThat(d.assignedBy()).isEqualTo(ADMIN);

        assertThatThrownBy(() -> service.delegate(source.id(), "u-other", null, null, NOW.plusSeconds(3600),
                "wrong region", ADMIN, delegationAdmin(regions("R2")))).satisfies(code("ASSIGNMENT_NOT_FOUND"));
        assertThatThrownBy(() -> service.delegate(source.id(), "u-other", null, null, NOW.plusSeconds(3600),
                "can read only", ADMIN, admin(Scopes.NATIONAL))).satisfies(code("DELEGATION_NOT_ALLOWED"))
                .satisfies(t -> assertThat(((DomainException) t).kind())
                        .isEqualTo(DomainException.Kind.NOT_PERMITTED));
        assertThatThrownBy(() -> service.delegate(source.id(), "u-admin", null, null, NOW.plusSeconds(3600),
                "to myself", ADMIN, delegationAdmin(Scopes.NATIONAL))).satisfies(code("SELF_DELEGATION"));
        assertThatThrownBy(() -> service.delegate(source.id(), "u-x", null, null, NOW.plusSeconds(3600),
                "not mine", new Actor("u-stranger", null), plain("u-stranger")))
                .satisfies(code("ASSIGNMENT_NOT_FOUND"));
        assertThatThrownBy(() -> service.delegate(d.id(), "u-x", null, null, NOW.plusSeconds(60), "chain",
                new Actor("u-deputy", null), plain("u-deputy"))).satisfies(code("REDELEGATION"));
        assertThatThrownBy(() -> service.delegate(UUID.randomUUID(), "u-x", null, null, NOW.plusSeconds(60),
                "missing", ADMIN, delegationAdmin(Scopes.NATIONAL))).satisfies(code("ASSIGNMENT_NOT_FOUND"));
    }

    @Test
    @Tag("CC-004")
    @DisplayName("CC-004: duplicate delegations and delegations of an inactive role are refused")
    void delegationConflicts() {
        RoleAssignment source = holderAssignment(null);
        service.delegate(source.id(), "u-deputy", null, null, NOW.plusSeconds(3600), "cover", HOLDER,
                plain("u-holder"));
        assertThatThrownBy(() -> service.delegate(source.id(), "u-deputy", null, null, NOW.plusSeconds(1800),
                "again", HOLDER, plain("u-holder"))).satisfies(code("ASSIGNMENT_EXISTS"));

        service.updateRole("REFDATA_MAKER", NAME, null, false, List.of(), 1, "retire", ADMIN, admin(Scopes.NATIONAL));
        assertThatThrownBy(() -> service.delegate(source.id(), "u-other", null, null, NOW.plusSeconds(3600),
                "cover", HOLDER, plain("u-holder"))).satisfies(code("ROLE_INACTIVE"));
    }

    @Test
    @Tag("CC-004")
    @DisplayName("CC-004: delegator, holder or an in-scope administrator revoke a delegation; others cannot; audited")
    void delegationRevocation() {
        RoleAssignment source = holderAssignment(null);
        RoleAssignment byHolder = service.delegate(source.id(), "u-deputy", null, null, NOW.plusSeconds(3600),
                "cover", HOLDER, plain("u-holder"));

        assertThatThrownBy(() -> service.revokeDelegation(byHolder.id(), "take it", new Actor("u-deputy", null),
                plain("u-deputy"))).satisfies(code("DELEGATION_NOT_ALLOWED"));
        assertThatThrownBy(() -> service.revokeDelegation(byHolder.id(), "nosy", new Actor("u-stranger", null),
                plain("u-stranger"))).satisfies(code("DELEGATION_NOT_FOUND"));
        assertThatThrownBy(() -> service.revokeDelegation(source.id(), "not a delegation", ADMIN,
                delegationAdmin(Scopes.NATIONAL))).satisfies(code("DELEGATION_NOT_FOUND"));

        RoleAssignment revoked = service.revokeDelegation(byHolder.id(), "back early", HOLDER, plain("u-holder"));
        assertThat(revoked.status()).isEqualTo(RoleAssignment.Status.REVOKED);
        assertThat(service.effectiveAuthorisation("u-deputy").grants()).isEmpty();
        assertThat(audit.actions()).endsWith("IAM_DELEGATION_REVOKED");

        // An administrator's delegation can be revoked by the holder, and the administrator can revoke the holder's.
        RoleAssignment byAdmin = service.delegate(source.id(), "u-deputy", null, null, NOW.plusSeconds(3600),
                "holder is ill", ADMIN, delegationAdmin(regions("R1")));
        assertThat(service.revokeDelegation(byAdmin.id(), "I am back", HOLDER, plain("u-holder")).status())
                .isEqualTo(RoleAssignment.Status.REVOKED);
        RoleAssignment third = service.delegate(source.id(), "u-deputy", null, null, NOW.plusSeconds(3600), "cover",
                HOLDER, plain("u-holder"));
        assertThatThrownBy(() -> service.revokeDelegation(third.id(), "wrong region", ADMIN,
                delegationAdmin(regions("R2")))).satisfies(code("DELEGATION_NOT_FOUND"));
        assertThat(service.revokeDelegation(third.id(), "audit finding", ADMIN, delegationAdmin(regions("R1")))
                .revokedBy()).isEqualTo(ADMIN);
    }

    @Test
    @Tag("CC-004")
    @DisplayName("CC-004: parties see their delegations; others only within iam.delegation.read scope")
    void delegationVisibility() {
        RoleAssignment source = holderAssignment(null);
        RoleAssignment d = service.delegate(source.id(), "u-deputy", null, null, NOW.plusSeconds(3600), "cover",
                HOLDER, plain("u-holder"));

        assertThat(service.delegations(null, plain("u-holder"))).extracting(RoleAssignment::id).containsExactly(d.id());
        assertThat(service.delegations(null, plain("u-deputy"))).extracting(RoleAssignment::id).containsExactly(d.id());
        assertThat(service.delegations("u-holder", plain("u-stranger"))).isEmpty();
        assertThat(service.delegations("u-deputy", delegationAdmin(regions("R1")))).hasSize(1);
        assertThat(service.delegations("u-deputy", delegationAdmin(regions("R2")))).isEmpty();
        assertThat(service.delegations(null, plain("u-stranger"))).isEmpty();
        assertThatThrownBy(() -> service.delegations("bad id!", plain("u-holder"))).satisfies(code("INVALID_SUBJECT"));
    }
}

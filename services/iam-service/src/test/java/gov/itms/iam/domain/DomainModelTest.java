package gov.itms.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DomainModelTest {

    private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
    private static final Actor ADMIN = new Actor("u-admin", "admin");
    private static final BilingualName NAME = new BilingualName("دور", "Role");

    private static Consumer<Throwable> code(String expected) {
        return t -> assertThat(((DomainException) t).code()).isEqualTo(expected);
    }

    private static RoleAssignment assignment(String subject, Instant from, Instant to) {
        return RoleAssignment.assign(subject, null, "REFDATA_MAKER", AssignmentScopes.national(), from, to,
                "new joiner", ADMIN, NOW);
    }

    // ------------------------------------------------------------------ roles

    @Test
    @Tag("CC-005")
    @DisplayName("CC-005: a role is created at version 1 and each redefinition creates the next version")
    void roleVersions() {
        Role role = Role.create("CENTRE_AGENT", NAME, "  Front counter  ", List.of("txn.create", "txn.read"));
        assertThat(role.version()).isEqualTo(1);
        assertThat(role.active()).isTrue();
        assertThat(role.description()).isEqualTo("Front counter");
        assertThat(role.permissions()).containsExactly("txn.create", "txn.read");

        Role v2 = role.redefine(NAME, " ", false, List.of("txn.read"));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.active()).isFalse();
        assertThat(v2.description()).isNull();
        assertThat(v2.permissions()).containsExactly("txn.read");
    }

    @Test
    @Tag("CC-005")
    @DisplayName("CC-005: role codes, permission codes and sizes are validated")
    void roleValidation() {
        assertThatThrownBy(() -> Role.create("lower", NAME, null, List.of())).satisfies(code("INVALID_ROLE_CODE"));
        assertThatThrownBy(() -> Role.create("R", NAME, null, List.of())).satisfies(code("INVALID_ROLE_CODE"));
        assertThatThrownBy(() -> Role.create("GOOD_ROLE", NAME, null, List.of("ROLE_ADMIN")))
                .satisfies(code("INVALID_PERMISSION_CODE"));
        assertThatThrownBy(() -> Role.create("GOOD_ROLE", null, null, List.of())).satisfies(code("NAME_REQUIRED"));
        assertThatThrownBy(() -> Role.create("GOOD_ROLE", NAME, "x".repeat(1001), List.of()))
                .satisfies(code("DESCRIPTION_TOO_LONG"));
        assertThatThrownBy(() -> Role.create("GOOD_ROLE", NAME, null, null)).satisfies(code("PERMISSIONS_REQUIRED"));
        List<String> tooMany = IntStream.range(0, 501).mapToObj(i -> "svc.p" + i).toList();
        assertThatThrownBy(() -> Role.create("GOOD_ROLE", NAME, null, tooMany)).satisfies(code("TOO_MANY_PERMISSIONS"));
        assertThatThrownBy(() -> new BilingualName(" ", "x")).satisfies(code("NAME_REQUIRED"));
        assertThatThrownBy(() -> new BilingualName("x".repeat(201), "x")).satisfies(code("NAME_TOO_LONG"));
    }

    // ------------------------------------------------------------------ scopes

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: every scope dimension needs at least one value, and * stands alone")
    void scopeRules() {
        AssignmentScopes ok = AssignmentScopes.of(List.of("R2", "R1"), List.of("*"), List.of("VEHICLES"));
        assertThat(ok.regions()).containsExactly("R1", "R2");

        assertThatThrownBy(() -> AssignmentScopes.of(List.of(), List.of("*"), List.of("*")))
                .satisfies(code("SCOPE_REQUIRED"));
        assertThatThrownBy(() -> AssignmentScopes.of(List.of("*"), null, List.of("*")))
                .satisfies(code("SCOPE_REQUIRED"));
        assertThatThrownBy(() -> AssignmentScopes.of(List.of("*", "R1"), List.of("*"), List.of("*")))
                .satisfies(code("SCOPE_ALL_EXCLUSIVE"));
        assertThatThrownBy(() -> AssignmentScopes.of(List.of("r1"), List.of("*"), List.of("*")))
                .satisfies(code("SCOPE_VALUE_INVALID"));
        List<String> tooMany = IntStream.range(0, 101).mapToObj(i -> "R" + i).toList();
        assertThatThrownBy(() -> AssignmentScopes.of(tooMany, List.of("*"), List.of("*")))
                .satisfies(code("SCOPE_TOO_LARGE"));
    }

    // ------------------------------------------------------------------ assignments

    @Test
    @Tag("CC-002")
    @DisplayName("CC-002: nobody can assign a role to themselves")
    void noSelfAssignment() {
        assertThatThrownBy(() -> assignment("u-admin", null, null))
                .isInstanceOf(DomainException.class)
                .satisfies(code("SELF_ASSIGNMENT"))
                .satisfies(t -> assertThat(((DomainException) t).kind())
                        .isEqualTo(DomainException.Kind.SEGREGATION_OF_DUTIES));
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: an assignment is effective only inside its validity period while active")
    void validityWindow() {
        RoleAssignment a = assignment("u-1", NOW.plusSeconds(3600), NOW.plusSeconds(7200));
        assertThat(a.isEffectiveAt(NOW)).isFalse();
        assertThat(a.isEffectiveAt(NOW.plusSeconds(3600))).isTrue();
        assertThat(a.isEffectiveAt(NOW.plusSeconds(7200))).as("end is exclusive").isFalse();

        RoleAssignment open = assignment("u-1", null, null);
        assertThat(open.validFrom()).isEqualTo(NOW);
        assertThat(open.isEffectiveAt(NOW.plusSeconds(86400 * 365L))).isTrue();

        assertThatThrownBy(() -> assignment("u-1", NOW, NOW)).satisfies(code("VALIDITY_INVALID"));
        assertThatThrownBy(() -> assignment("u-1", NOW.minusSeconds(100), NOW.minusSeconds(10)))
                .satisfies(code("VALIDITY_IN_PAST"));
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: revocation ends the assignment once, with a reason")
    void revocation() {
        RoleAssignment a = assignment("u-1", null, null);
        assertThatThrownBy(() -> a.revoke(ADMIN, "x", NOW)).satisfies(code("REASON_REQUIRED"));

        a.revoke(ADMIN, "left the unit", NOW.plusSeconds(10));
        assertThat(a.status()).isEqualTo(RoleAssignment.Status.REVOKED);
        assertThat(a.revocationReason()).isEqualTo("left the unit");
        assertThat(a.isEffectiveAt(NOW.plusSeconds(20))).isFalse();
        assertThatThrownBy(() -> a.revoke(ADMIN, "again please", NOW)).satisfies(code("ASSIGNMENT_NOT_ACTIVE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "x/y"})
    @Tag("CC-001")
    void invalidSubjects(String subject) {
        assertThatThrownBy(() -> assignment(subject, null, null)).satisfies(code("INVALID_SUBJECT"));
    }

    @Test
    void reasonAndActorValidation() {
        assertThatThrownBy(() -> Codes.reason("x".repeat(501))).satisfies(code("REASON_TOO_LONG"));
        assertThatThrownBy(() -> new Actor(" ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Permission("iam.role.read", "iam-service", NAME).code()).isEqualTo("iam.role.read");
        assertThat(Set.of(DomainException.Kind.values())).hasSize(6);
        assertThat(DomainException.notPermitted("X", "x", "CC-004").requirementRefs()).containsExactly("CC-004");
    }

    // ------------------------------------------------------------------ approval limits (CC-003)

    @Test
    @Tag("CC-003")
    @DisplayName("CC-003: an approval limit has a coded type, an ISO 4217 currency and a non-negative amount")
    void approvalLimitRules() {
        assertThat(new ApprovalLimit("REFUND_AMOUNT", 0, "XTS").maxAmountMinor()).isZero();
        assertThat(new ApprovalLimit("FINE_REDUCTION_AMOUNT", Long.MAX_VALUE, "EUR").currency()).isEqualTo("EUR");
        assertThatThrownBy(() -> new ApprovalLimit("refund", 1, "XTS")).satisfies(code("LIMIT_TYPE_INVALID"));
        assertThatThrownBy(() -> new ApprovalLimit(null, 1, "XTS")).satisfies(code("LIMIT_TYPE_INVALID"));
        assertThatThrownBy(() -> new ApprovalLimit("A".repeat(41), 1, "XTS")).satisfies(code("LIMIT_TYPE_INVALID"));
        assertThatThrownBy(() -> new ApprovalLimit("REFUND_AMOUNT", 1, "xts")).satisfies(code("CURRENCY_INVALID"));
        assertThatThrownBy(() -> new ApprovalLimit("REFUND_AMOUNT", 1, null)).satisfies(code("CURRENCY_INVALID"));
        assertThatThrownBy(() -> new ApprovalLimit("REFUND_AMOUNT", -1, "XTS"))
                .satisfies(code("LIMIT_AMOUNT_NEGATIVE"));
    }

    @Test
    @Tag("CC-003")
    @DisplayName("CC-003: a role's limit set lists each type and currency once, at most 100, in a stable order")
    void approvalLimitSet() {
        ApprovalLimit refundXts = new ApprovalLimit("REFUND_AMOUNT", 100, "XTS");
        ApprovalLimit refundEur = new ApprovalLimit("REFUND_AMOUNT", 100, "EUR");
        ApprovalLimit fine = new ApprovalLimit("FINE_REDUCTION_AMOUNT", 5, "XTS");

        assertThat(ApprovalLimit.validSet(List.of(refundXts, fine, refundEur)))
                .containsExactly(fine, refundEur, refundXts);
        assertThat(ApprovalLimit.validSet(List.of())).isEmpty();
        assertThatThrownBy(() -> ApprovalLimit.validSet(List.of(refundXts, new ApprovalLimit("REFUND_AMOUNT", 9,
                "XTS")))).satisfies(code("LIMIT_DUPLICATE"));
        assertThatThrownBy(() -> ApprovalLimit.validSet(null)).satisfies(code("LIMITS_REQUIRED"));
        List<ApprovalLimit> tooMany = IntStream.rangeClosed(0, ApprovalLimit.MAX_PER_ROLE)
                .mapToObj(i -> new ApprovalLimit("T" + i, i, "XTS")).toList();
        assertThatThrownBy(() -> ApprovalLimit.validSet(tooMany)).satisfies(code("TOO_MANY_LIMITS"));
    }

    // ------------------------------------------------------------------ delegation (CC-004)

    private static final Actor HOLDER = new Actor("u-holder", "holder");

    @Test
    @Tag("CC-004")
    @DisplayName("CC-004: a delegation copies the source's role and scopes for a set period and points at the source")
    void delegationCopiesSource() {
        RoleAssignment source = assignment("u-holder", null, NOW.plusSeconds(86_400));
        RoleAssignment d = RoleAssignment.delegate(source, "u-deputy", "deputy", null, NOW.plusSeconds(3600),
                "annual leave", HOLDER, NOW);

        assertThat(d.isDelegation()).isTrue();
        assertThat(d.source()).isEqualTo(RoleAssignment.Source.DELEGATION);
        assertThat(d.delegatedFrom()).isEqualTo(source.id());
        assertThat(d.roleCode()).isEqualTo(source.roleCode());
        assertThat(d.scopes()).isEqualTo(source.scopes());
        assertThat(d.subjectId()).isEqualTo("u-deputy");
        assertThat(d.assignedBy()).isEqualTo(HOLDER);
        assertThat(d.validFrom()).isEqualTo(NOW);
        assertThat(source.isDelegation()).isFalse();
        assertThat(source.delegatedFrom()).isNull();
        // The delegation may end exactly when the source ends.
        assertThat(RoleAssignment.delegate(source, "u-deputy", null, null, source.validTo(), "cover", HOLDER, NOW)
                .validTo()).isEqualTo(source.validTo());
    }

    @Test
    @Tag("CC-004")
    @Tag("CC-002")
    @DisplayName("CC-004: no re-delegation, no self- or holder-delegation, an end is required and bounded by the source")
    void delegationRules() {
        RoleAssignment open = assignment("u-holder", null, null);
        RoleAssignment bounded = assignment("u-holder", null, NOW.plusSeconds(3600));
        Instant hour = NOW.plusSeconds(3600);
        RoleAssignment delegation = RoleAssignment.delegate(open, "u-deputy", null, null, hour, "cover", HOLDER, NOW);

        assertThatThrownBy(() -> RoleAssignment.delegate(delegation, "u-third", null, null, hour, "again",
                new Actor("u-deputy", null), NOW)).satisfies(code("REDELEGATION"));
        assertThatThrownBy(() -> RoleAssignment.delegate(open, "u-holder", null, null, hour, "self", HOLDER, NOW))
                .satisfies(code("SELF_DELEGATION"))
                .satisfies(t -> assertThat(((DomainException) t).kind())
                        .isEqualTo(DomainException.Kind.SEGREGATION_OF_DUTIES));
        assertThatThrownBy(() -> RoleAssignment.delegate(open, "u-holder", null, null, hour, "to holder", ADMIN, NOW))
                .satisfies(code("DELEGATE_IS_HOLDER"));
        assertThatThrownBy(() -> RoleAssignment.delegate(open, "u-deputy", null, null, null, "open-ended", HOLDER,
                NOW)).satisfies(code("DELEGATION_END_REQUIRED"));
        assertThatThrownBy(() -> RoleAssignment.delegate(bounded, "u-deputy", null, null, hour.plusSeconds(1),
                "too long", HOLDER, NOW)).satisfies(code("DELEGATION_EXCEEDS_SOURCE"));
        assertThatThrownBy(() -> RoleAssignment.delegate(open, "u-deputy", null, NOW.minusSeconds(60),
                NOW.minusSeconds(1), "past", HOLDER, NOW)).satisfies(code("VALIDITY_IN_PAST"));
        assertThatThrownBy(() -> RoleAssignment.delegate(open, "bad id!", null, null, hour, "x y z", HOLDER, NOW))
                .satisfies(code("INVALID_SUBJECT"));
    }

    @Test
    @Tag("CC-004")
    @DisplayName("CC-004: revoked or expired assignments cannot be delegated; future ones can")
    void delegationNeedsLiveSource() {
        RoleAssignment revoked = assignment("u-holder", null, null);
        revoked.revoke(ADMIN, "left", NOW);
        RoleAssignment expiring = assignment("u-holder", null, NOW.plusSeconds(60));
        RoleAssignment future = assignment("u-holder", NOW.plusSeconds(600), null);

        assertThatThrownBy(() -> RoleAssignment.delegate(revoked, "u-deputy", null, null, NOW.plusSeconds(30),
                "cover", HOLDER, NOW)).satisfies(code("SOURCE_NOT_ACTIVE"));
        assertThatThrownBy(() -> RoleAssignment.delegate(expiring, "u-deputy", null, null, NOW.plusSeconds(120),
                "cover", HOLDER, NOW.plusSeconds(60))).satisfies(code("SOURCE_NOT_ACTIVE"));
        assertThat(future.isActiveOrUpcomingAt(NOW)).isTrue();
        assertThat(RoleAssignment.delegate(future, "u-deputy", null, NOW.plusSeconds(600), NOW.plusSeconds(1200),
                "cover", HOLDER, NOW).validFrom()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    @Tag("CC-004")
    @DisplayName("CC-004: a rehydrated assignment has a source assignment exactly when it is a delegation")
    void delegationShapeIsConsistent() {
        java.util.UUID id = java.util.UUID.randomUUID();
        assertThatThrownBy(() -> new RoleAssignment(id, "u-1", null, "REFDATA_MAKER", AssignmentScopes.national(),
                NOW, null, "reason", ADMIN, NOW, RoleAssignment.Status.ACTIVE, null, null, null,
                RoleAssignment.Source.DELEGATION, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoleAssignment(id, "u-1", null, "REFDATA_MAKER", AssignmentScopes.national(),
                NOW, null, "reason", ADMIN, NOW, RoleAssignment.Status.ACTIVE, null, null, null,
                RoleAssignment.Source.DIRECT, id)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ conflicting roles (CC-002)

    @Test
    @Tag("CC-002")
    @DisplayName("CC-002: a conflicting pair is stored in alphabetical order and knows both of its roles")
    void roleConflictOrder() {
        RoleConflict pair = RoleConflict.declare("REFDATA_MAKER", "REFDATA_CHECKER", "  maker-checker  ", ADMIN, NOW);

        assertThat(pair.roleA()).isEqualTo("REFDATA_CHECKER");
        assertThat(pair.roleB()).isEqualTo("REFDATA_MAKER");
        assertThat(pair.reason()).isEqualTo("maker-checker");
        assertThat(pair.status()).isEqualTo(RoleConflict.Status.ACTIVE);
        assertThat(pair.createdBy()).isEqualTo(ADMIN);
        assertThat(pair.createdAt()).isEqualTo(NOW);
        assertThat(pair.involves("REFDATA_MAKER")).isTrue();
        assertThat(pair.involves("IT_ADMINISTRATOR")).isFalse();
        assertThat(pair.otherThan("REFDATA_MAKER")).isEqualTo("REFDATA_CHECKER");
        assertThat(pair.otherThan("REFDATA_CHECKER")).isEqualTo("REFDATA_MAKER");
        assertThatThrownBy(() -> pair.otherThan("IT_ADMINISTRATOR")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("CC-002")
    @DisplayName("CC-002: a role cannot conflict with itself; codes and reason are validated")
    void roleConflictValidation() {
        assertThatThrownBy(() -> RoleConflict.declare("REFDATA_MAKER", "REFDATA_MAKER", "same", ADMIN, NOW))
                .satisfies(code("ROLE_CONFLICT_SAME_ROLE"));
        assertThatThrownBy(() -> RoleConflict.declare("bad", "REFDATA_MAKER", "reason", ADMIN, NOW))
                .satisfies(code("INVALID_ROLE_CODE"));
        assertThatThrownBy(() -> RoleConflict.declare("REFDATA_CHECKER", "REFDATA_MAKER", " ", ADMIN, NOW))
                .satisfies(code("REASON_REQUIRED"));
        java.util.UUID id = java.util.UUID.randomUUID();
        assertThatThrownBy(() -> new RoleConflict(id, "REFDATA_MAKER", "REFDATA_CHECKER", "reason", ADMIN, NOW,
                RoleConflict.Status.ACTIVE, null, null, null)).as("unordered rehydration")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("CC-002")
    @DisplayName("CC-002: a pair is retired once, with a reason")
    void roleConflictRetirement() {
        RoleConflict pair = RoleConflict.declare("REFDATA_CHECKER", "REFDATA_MAKER", "policy", ADMIN, NOW);
        assertThatThrownBy(() -> pair.retire(ADMIN, "", NOW)).satisfies(code("REASON_REQUIRED"));
        assertThat(pair.status()).isEqualTo(RoleConflict.Status.ACTIVE);

        pair.retire(ADMIN, "policy withdrawn", NOW.plusSeconds(60));
        assertThat(pair.status()).isEqualTo(RoleConflict.Status.RETIRED);
        assertThat(pair.retiredBy()).isEqualTo(ADMIN);
        assertThat(pair.retiredAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(pair.retirementReason()).isEqualTo("policy withdrawn");
        assertThatThrownBy(() -> pair.retire(ADMIN, "again", NOW)).satisfies(code("ROLE_CONFLICT_NOT_ACTIVE"));
    }
}

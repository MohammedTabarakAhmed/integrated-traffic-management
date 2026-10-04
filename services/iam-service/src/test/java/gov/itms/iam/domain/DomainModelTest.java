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
        assertThat(Set.of(DomainException.Kind.values())).hasSize(5);
    }
}

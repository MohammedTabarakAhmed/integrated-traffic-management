package gov.itms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class AuthorisationTest {

    private static Scopes scopes(Set<String> regions, Set<String> centres, Set<String> modules) {
        return new Scopes(regions, centres, modules);
    }

    private static Authorisation.Grant grant(String role, Scopes scopes, String... permissions) {
        return new Authorisation.Grant(role, Set.of(permissions), scopes, null);
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: a grant reaches a target only inside its region, centre and module scopes")
    void allowsWithinScopeOnly() {
        Scopes r1c1 = scopes(Set.of("R1"), Set.of("C1"), Set.of(Scopes.ALL));
        Authorisation a = new Authorisation("u1", List.of(grant("AGENT", r1c1, "txn.create")), Instant.EPOCH);

        assertThat(a.allows("txn.create", new ScopeTarget("R1", "C1", "VEHICLES"))).isTrue();
        assertThat(a.allows("txn.create", ScopeTarget.region("R1"))).isTrue();
        assertThat(a.allows("txn.create", ScopeTarget.UNSCOPED)).isTrue();
        assertThat(a.allows("txn.create", new ScopeTarget("R1", "C2", null))).isFalse();
        assertThat(a.allows("txn.create", ScopeTarget.region("R2"))).isFalse();
        assertThat(a.allows("txn.approve", ScopeTarget.region("R1"))).isFalse();
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: grants are never merged, so two assignments cannot combine into a wider scope")
    void grantsAreNotMerged() {
        Authorisation a = new Authorisation("u1", List.of(
                grant("A", scopes(Set.of("R1"), Set.of("C1"), Set.of(Scopes.ALL)), "txn.create"),
                grant("B", scopes(Set.of("R2"), Set.of("C2"), Set.of(Scopes.ALL)), "txn.create")), Instant.EPOCH);

        assertThat(a.allows("txn.create", new ScopeTarget("R1", "C1", null))).isTrue();
        assertThat(a.allows("txn.create", new ScopeTarget("R2", "C2", null))).isTrue();
        assertThat(a.allows("txn.create", new ScopeTarget("R1", "C2", null))).isFalse();
        assertThat(a.permissions()).containsExactly("txn.create");
        assertThat(a.roles()).containsExactly("A", "B");
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: covers() refuses granting beyond the holder's own scope, and only * covers *")
    void coversRequestedScopes() {
        Scopes regional = scopes(Set.of("R1", "R2"), Set.of(Scopes.ALL), Set.of("VEHICLES"));
        Authorisation a = new Authorisation("admin", List.of(grant("IT", regional, "iam.assignment.manage")),
                Instant.EPOCH);

        assertThat(a.covers("iam.assignment.manage", scopes(Set.of("R1"), Set.of("C9"), Set.of("VEHICLES")))).isTrue();
        assertThat(a.covers("iam.assignment.manage", scopes(Set.of("R1", "R2"), Set.of(Scopes.ALL), Set.of("VEHICLES"))))
                .isTrue();
        assertThat(a.covers("iam.assignment.manage", scopes(Set.of("R3"), Set.of("C1"), Set.of("VEHICLES")))).isFalse();
        assertThat(a.covers("iam.assignment.manage", scopes(Set.of(Scopes.ALL), Set.of("C1"), Set.of("VEHICLES"))))
                .isFalse();
        assertThat(a.covers("iam.assignment.manage", scopes(Set.of("R1"), Set.of("C1"), Set.of("LICENSING")))).isFalse();
        assertThat(a.covers("iam.role.manage", scopes(Set.of("R1"), Set.of("C1"), Set.of("VEHICLES")))).isFalse();
        assertThat(Scopes.NATIONAL.covers(regional)).isTrue();
    }
}

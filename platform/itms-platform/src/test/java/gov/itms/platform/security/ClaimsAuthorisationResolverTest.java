package gov.itms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class ClaimsAuthorisationResolverTest {

    private static Jwt jwt(Object permissions, Object roles) {
        var builder = Jwt.withTokenValue("t").header("alg", "none").subject("u1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (permissions != null) {
            builder.claim("permissions", permissions);
        }
        if (roles != null) {
            builder.claim("roles", roles);
        }
        return builder.build();
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: interim mode maps well-formed permission claims only, with national scope")
    void mapsWellFormedPermissionsOnly() {
        Authorisation a = new ClaimsAuthorisationResolver("permissions", "roles").resolve(
                jwt(List.of("refdata.read", "refdata.change.approve", "ROLE_ADMIN", "x", "refdata.read"),
                        List.of("REFDATA_CHECKER")));

        assertThat(a.permissions()).containsExactly("refdata.change.approve", "refdata.read");
        assertThat(a.roles()).containsExactly("REFDATA_CHECKER");
        assertThat(a.allows("refdata.read", ScopeTarget.region("ANY"))).isTrue();
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: a token without the claim grants nothing")
    void missingClaimGrantsNothing() {
        Authorisation a = new ClaimsAuthorisationResolver("permissions", "roles").resolve(jwt(null, null));
        assertThat(a.grants()).isEmpty();
        assertThat(a.subjectId()).isEqualTo("u1");
    }

    @Test
    @Tag("SEC-001")
    @DisplayName("SEC-001: tokens for another audience are rejected")
    void audienceValidatorRejectsTokensForOtherAudiences() {
        var validator = ItmsSecurityAutoConfiguration.audienceValidator("itms-api");
        Jwt ok = Jwt.withTokenValue("t").header("alg", "none").subject("u").audience(List.of("itms-api")).build();
        Jwt other = Jwt.withTokenValue("t").header("alg", "none").subject("u").audience(List.of("account")).build();

        assertThat(validator.validate(ok).hasErrors()).isFalse();
        assertThat(validator.validate(other).hasErrors()).isTrue();
    }
}

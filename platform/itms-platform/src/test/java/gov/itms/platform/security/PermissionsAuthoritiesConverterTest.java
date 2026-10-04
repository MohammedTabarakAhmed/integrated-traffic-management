package gov.itms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class PermissionsAuthoritiesConverterTest {

    private static Jwt jwt(Object permissions) {
        var builder = Jwt.withTokenValue("t").header("alg", "none").subject("u1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (permissions != null) {
            builder.claim("permissions", permissions);
        }
        return builder.build();
    }

    @Test
    @Tag("CC-001")
    void mapsWellFormedPermissionsOnly() {
        var authorities = new PermissionsAuthoritiesConverter("permissions")
                .convert(jwt(List.of("refdata.read", "refdata.change.approve", "ROLE_ADMIN", "x", "refdata.read")));

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactly("refdata.read", "refdata.change.approve");
    }

    @Test
    @Tag("CC-001")
    void missingClaimGivesNoAuthorities() {
        assertThat(new PermissionsAuthoritiesConverter("permissions").convert(jwt(null))).isEmpty();
    }

    @Test
    @Tag("SEC-001")
    void audienceValidatorRejectsTokensForOtherAudiences() {
        var validator = ItmsSecurityAutoConfiguration.audienceValidator("itms-api");
        Jwt ok = Jwt.withTokenValue("t").header("alg", "none").subject("u").audience(List.of("itms-api")).build();
        Jwt other = Jwt.withTokenValue("t").header("alg", "none").subject("u").audience(List.of("account")).build();

        assertThat(validator.validate(ok).hasErrors()).isFalse();
        assertThat(validator.validate(other).hasErrors()).isTrue();
    }
}

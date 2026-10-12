package gov.itms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ItmsSecurityPropertiesTest {

    private static final Duration S2 = Duration.ofSeconds(2);

    @Test
    @Tag("NFR-042")
    @DisplayName("NFR-042: the iam lookup settings are refused outside their approved ranges")
    void iamSettingsWithinRange() {
        assertThat(new ItmsSecurityProperties.Iam("http://iam", Duration.ofSeconds(30), Duration.ofMinutes(5), 1)
                .cacheMaxEntries()).isEqualTo(1);
        assertThat(new ItmsSecurityProperties.Iam("http://iam", S2, Duration.ZERO, 1_000_000).cacheTtl()).isZero();

        assertThatThrownBy(() -> new ItmsSecurityProperties.Iam("http://iam", Duration.ZERO, S2, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timeout");
        assertThatThrownBy(() -> new ItmsSecurityProperties.Iam("http://iam", Duration.ofSeconds(31), S2, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timeout");
        assertThatThrownBy(() -> new ItmsSecurityProperties.Iam("http://iam", null, S2, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItmsSecurityProperties.Iam("http://iam", S2, Duration.ofMinutes(6), 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cache-ttl");
        assertThatThrownBy(() -> new ItmsSecurityProperties.Iam("http://iam", S2, Duration.ofSeconds(-1), 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cache-ttl");
        assertThatThrownBy(() -> new ItmsSecurityProperties.Iam("http://iam", S2, S2, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cache-max-entries");
        assertThatThrownBy(() -> new ItmsSecurityProperties.Iam("http://iam", S2, S2, 1_000_001))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cache-max-entries");
    }

    @Test
    @Tag("CC-083")
    @Tag("SEC-001")
    @DisplayName("CC-083, SEC-001: public paths are empty by default and must be /v1/ paths without '..'")
    void publicPaths() {
        ItmsSecurityProperties.Iam iam = new ItmsSecurityProperties.Iam("http://iam", S2, S2, 10);
        assertThat(new ItmsSecurityProperties("https://idp", "https://idp/jwks", "itms-api",
                ItmsSecurityProperties.AuthorisationSource.IAM, "permissions", "roles", iam, null, null).publicPaths())
                .isEmpty();
        assertThat(new ItmsSecurityProperties("https://idp", "https://idp/jwks", "itms-api",
                ItmsSecurityProperties.AuthorisationSource.IAM, "permissions", "roles", iam,
                java.util.List.of("/v1/certificate-verifications/*"), null).publicPaths())
                .containsExactly("/v1/certificate-verifications/*");
        for (String bad : new String[] {"/actuator/**", "/**", "/v1/../admin", "v1/x"}) {
            assertThatThrownBy(() -> new ItmsSecurityProperties("https://idp", "https://idp/jwks", "itms-api",
                    ItmsSecurityProperties.AuthorisationSource.IAM, "permissions", "roles", iam,
                    java.util.List.of(bad), null)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("public-paths");
        }
    }

    @Test
    @Tag("SEC-002")
    @DisplayName("SEC-002 (ADR-067): customer step-up accepts otp and protects no path by default; entries are checked")
    void stepUp() {
        ItmsSecurityProperties.Iam iam = new ItmsSecurityProperties.Iam("http://iam", S2, S2, 10);
        ItmsSecurityProperties.StepUp defaults = new ItmsSecurityProperties("https://idp", "https://idp/jwks",
                "itms-api", ItmsSecurityProperties.AuthorisationSource.IAM, "permissions", "roles", iam, null, null)
                .stepUp();
        assertThat(defaults.acrValues()).containsExactly("otp");
        assertThat(defaults.paths()).isEmpty();
        assertThat(new ItmsSecurityProperties.StepUp(java.util.List.of("otp", "urn:mace:loa:3"),
                java.util.List.of("POST /v1/me/payments", "PUT /v1/me/profile/**")).paths()).hasSize(2);
        assertThatThrownBy(() -> new ItmsSecurityProperties.StepUp(java.util.List.of(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("acr-values");
        assertThatThrownBy(() -> new ItmsSecurityProperties.StepUp(java.util.List.of("bad value"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("acr-values");
        for (String bad : new String[] {"/v1/me/payments", "TRACE /v1/x", "POST /actuator/x", "POST /v1/../admin"}) {
            assertThatThrownBy(() -> new ItmsSecurityProperties.StepUp(java.util.List.of("otp"),
                    java.util.List.of(bad))).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("step-up.paths");
        }
    }
}

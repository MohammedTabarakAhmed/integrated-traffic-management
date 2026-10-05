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
}

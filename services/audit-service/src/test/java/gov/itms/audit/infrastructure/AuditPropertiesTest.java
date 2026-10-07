package gov.itms.audit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.audit.infrastructure.AuditServiceConfiguration.AuditProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("NFR-042")
@Tag("CC-122")
class AuditPropertiesTest {

    @Test
    @DisplayName("NFR-042, CC-122: retention 1-30 years (BRD default 8), purge interval 1 h-7 days, batch 1-100000")
    void ranges() {
        assertThat(new AuditProperties(Duration.ofDays(2922), Duration.ofHours(24), 10_000).retention())
                .isEqualTo(Duration.ofDays(2922));
        assertThat(new AuditProperties(Duration.ofDays(365), Duration.ofHours(1), 1).purgeBatch()).isEqualTo(1);
        assertThatThrownBy(() -> new AuditProperties(Duration.ofDays(364), Duration.ofHours(24), 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retention");
        assertThatThrownBy(() -> new AuditProperties(Duration.ofDays(30 * 366 + 1), Duration.ofHours(24), 10))
                .hasMessageContaining("retention");
        assertThatThrownBy(() -> new AuditProperties(Duration.ofDays(2922), Duration.ofMinutes(59), 10))
                .hasMessageContaining("purge-interval");
        assertThatThrownBy(() -> new AuditProperties(Duration.ofDays(2922), Duration.ofDays(8), 10))
                .hasMessageContaining("purge-interval");
        assertThatThrownBy(() -> new AuditProperties(Duration.ofDays(2922), Duration.ofHours(24), 0))
                .hasMessageContaining("purge-batch");
        assertThatThrownBy(() -> new AuditProperties(Duration.ofDays(2922), Duration.ofHours(24), 100_001))
                .hasMessageContaining("purge-batch");
    }
}

package gov.itms.violation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.violation.infrastructure.ViolationServiceConfiguration.ViolationProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("NFR-042")
@Tag("VF-031")
class ViolationPropertiesTest {

    private static ViolationProperties props(int expiry, int threshold, List<Integer> months, List<Integer> warnings,
                                             String currency, String zone, Duration delay) {
        return new ViolationProperties(expiry, threshold, months, warnings, null, currency, zone, delay, null, null,
                true, Duration.ofDays(60), Duration.ofHours(1), 100, null, null);
    }

    private static ViolationProperties unpaid(Duration after, Duration interval, int batch) {
        return new ViolationProperties(12, 24, List.of(3, 6, 12), List.of(12, 18), null, "XTS", "UTC",
                Duration.ofDays(30), null, null, true, after, interval, batch, null, null);
    }

    @Test
    @Tag("VF-021")
    @Tag("VF-032")
    @DisplayName("VF-021, VF-032: BRD defaults are accepted; reasons, blocked services and lifters have defaults")
    void defaults() {
        ViolationProperties p = props(12, 24, List.of(3, 6, 12), List.of(12, 18), "XTS", "UTC", Duration.ofDays(30));
        assertThat(p.cancelReasons()).contains("DUPLICATE", "WRONG_PLATE");
        assertThat(p.suspensionBlockedServices()).containsExactly("*");
        assertThat(p.suspensionLiftableByRoles()).containsExactly("HOLD_SUPERVISOR");
        assertThat(p.unpaidHoldBlockedServices()).containsExactly("VEH_RENEWAL");
        assertThat(p.unpaidHoldLiftableByRoles()).containsExactly("UNPAID_FINE_HOLD_MANAGER", "HOLD_SUPERVISOR");
    }

    @Test
    @Tag("VF-023")
    @DisplayName("VF-023: the unpaid period, job interval and batch size are range-checked")
    void unpaidRanges() {
        assertThatThrownBy(() -> unpaid(Duration.ofSeconds(30), Duration.ofHours(1), 100))
                .hasMessageContaining("unpaid");
        assertThatThrownBy(() -> unpaid(Duration.ofDays(60), Duration.ofSeconds(30), 100))
                .hasMessageContaining("unpaid-hold-interval");
        assertThatThrownBy(() -> unpaid(Duration.ofDays(60), Duration.ofDays(2), 100))
                .hasMessageContaining("unpaid-hold-interval");
        assertThatThrownBy(() -> unpaid(Duration.ofDays(60), Duration.ofHours(1), 0)).hasMessageContaining("batch");
        assertThat(unpaid(Duration.ofMinutes(1), Duration.ofMinutes(1), 1000).unpaidHoldAfter())
                .isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("NFR-042: currency, zone, report delay and black point settings are range-checked")
    void ranges() {
        List<Integer> m = List.of(3, 6, 12);
        List<Integer> w = List.of(12, 18);
        assertThatThrownBy(() -> props(12, 24, m, w, "xts", "UTC", Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
        assertThatThrownBy(() -> props(12, 24, m, w, "XTS", "Nowhere/City", Duration.ofDays(30)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> props(12, 24, m, w, "XTS", "UTC", Duration.ofMinutes(59)))
                .hasMessageContaining("max-report-delay");
        assertThatThrownBy(() -> props(12, 24, m, w, "XTS", "UTC", Duration.ofDays(366)))
                .hasMessageContaining("max-report-delay");
        assertThatThrownBy(() -> props(12, 24, m, List.of(30), "XTS", "UTC", Duration.ofDays(30)))
                .hasMessageContaining("black point");
        assertThatThrownBy(() -> props(0, 24, m, w, "XTS", "UTC", Duration.ofDays(30)))
                .hasMessageContaining("black point");
    }
}

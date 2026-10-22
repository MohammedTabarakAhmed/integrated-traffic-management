package gov.itms.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * RPT-012: the domain rules of the KPI setting itself, before any HTTP: the shapes accepted and the
 * red/amber/green evaluation by the thresholds, in both directions.
 */
@Tag("RPT-012")
class KpiSettingTest {

    private static KpiSetting set(long target, long red, long amber, boolean higher) {
        return KpiSetting.set("VIOLATIONS_PAID_PCT", target, red, amber, higher, "u-1", Instant.EPOCH);
    }

    @Test
    @DisplayName("RPT-012: a KPI that counts towards the goal is RED below red_threshold, AMBER below amber, GREEN otherwise")
    void higherIsBetter() {
        KpiSetting s = set(95, 80, 90, true);
        assertThat(s.statusOf(70)).isEqualTo("RED");
        assertThat(s.statusOf(80)).isEqualTo("AMBER");
        assertThat(s.statusOf(89)).isEqualTo("AMBER");
        assertThat(s.statusOf(90)).isEqualTo("GREEN");
        assertThat(s.statusOf(95)).isEqualTo("GREEN");
    }

    @Test
    @DisplayName("RPT-012: a KPI that counts against the goal is RED above red_threshold, AMBER above amber, GREEN otherwise")
    void lowerIsBetter() {
        KpiSetting s = set(10, 40, 20, false);
        assertThat(s.statusOf(50)).isEqualTo("RED");
        assertThat(s.statusOf(40)).isEqualTo("AMBER");
        assertThat(s.statusOf(21)).isEqualTo("AMBER");
        assertThat(s.statusOf(20)).isEqualTo("GREEN");
        assertThat(s.statusOf(0)).isEqualTo("GREEN");
    }

    @Test
    @DisplayName("RPT-012: the code is shape-checked and the thresholds are ordered by the KPI's direction")
    void validation() {
        assertThatThrownBy(() -> KpiSetting.set("bad code", 95, 80, 90, true, "u-1", Instant.EPOCH))
                .isInstanceOf(DomainException.class).hasMessageContaining("KPI code");
        assertThatThrownBy(() -> set(95, 90, 80, true))
                .isInstanceOf(DomainException.class).hasMessageContaining("wrong order");
        assertThatThrownBy(() -> set(10, 20, 40, false))
                .isInstanceOf(DomainException.class).hasMessageContaining("wrong order");
        assertThat(set(95, 90, 90, true)).isNotNull(); // equal boundaries allowed: the amber band simply vanishes
        assertThat(set(10, 20, 20, false)).isNotNull();
    }
}

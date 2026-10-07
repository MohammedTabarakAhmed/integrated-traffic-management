package gov.itms.violation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("VF-030")
class BlackPointsTest {

    /** BRD defaults: 12 months, 24 points, 3/6/12 months, warnings at 12 and 18. */
    static final BlackPoints.Policy POLICY = new BlackPoints.Policy(12, 24, List.of(3, 6, 12), List.of(12, 18));
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    private static BlackPoints.Entry entry(int points, LocalDate on, BlackPoints.EntryStatus status) {
        return new BlackPoints.Entry(UUID.randomUUID(), "TF-1", UUID.randomUUID(), points, on, on.plusMonths(12),
                status);
    }

    @Test
    @Tag("VF-031")
    @DisplayName("VF-030, VF-031: only active, unexpired entries count; an entry expires on its expiry day")
    void activeTotal() {
        List<BlackPoints.Entry> entries = List.of(
                entry(6, DAY, BlackPoints.EntryStatus.ACTIVE),
                entry(4, DAY.minusMonths(12), BlackPoints.EntryStatus.ACTIVE),
                entry(8, DAY.minusMonths(11), BlackPoints.EntryStatus.ACTIVE),
                entry(10, DAY, BlackPoints.EntryStatus.CONSUMED),
                entry(12, DAY, BlackPoints.EntryStatus.CANCELLED));
        assertThat(BlackPoints.active(entries, DAY)).as("6 + 8; the 4 expired today").isEqualTo(14);
        assertThat(BlackPoints.active(entries, DAY.minusDays(1))).isEqualTo(18);
        assertThat(BlackPoints.active(List.of(), DAY)).isZero();
    }

    @Test
    @Tag("VF-033")
    @DisplayName("VF-033: warnings fire once, when a threshold is crossed upwards")
    void warningsCrossedOnce() {
        assertThat(BlackPoints.warningsCrossed(POLICY, 10, 12)).containsExactly(12);
        assertThat(BlackPoints.warningsCrossed(POLICY, 12, 14)).as("already past 12").isEmpty();
        assertThat(BlackPoints.warningsCrossed(POLICY, 8, 20)).containsExactly(12, 18);
        assertThat(BlackPoints.warningsCrossed(POLICY, 20, 26)).isEmpty();
        assertThat(BlackPoints.warningsCrossed(POLICY, 14, 10)).isEmpty();
    }

    @Test
    @Tag("VF-032")
    @DisplayName("VF-032: the n-th suspension lasts the n-th configured period; the last one repeats")
    void monthsByOrdinal() {
        assertThat(POLICY.monthsOf(1)).isEqualTo(3);
        assertThat(POLICY.monthsOf(2)).isEqualTo(6);
        assertThat(POLICY.monthsOf(3)).isEqualTo(12);
        assertThat(POLICY.monthsOf(7)).isEqualTo(12);
        BlackPoints.Suspension s = new BlackPoints.Suspension(UUID.randomUUID(), "TF-1", 1, DAY,
                DAY.plusMonths(3).minusDays(1), 26, "H-1", UUID.randomUUID());
        assertThat(s.activeOn(DAY)).isTrue();
        assertThat(s.activeOn(DAY.plusMonths(3).minusDays(1))).isTrue();
        assertThat(s.activeOn(DAY.plusMonths(3))).isFalse();
    }

    @Test
    @DisplayName("VF-031..VF-033: settings are range-checked; warnings must be below the suspension threshold")
    void policyRanges() {
        assertThatThrownBy(() -> new BlackPoints.Policy(0, 24, List.of(3), List.of())).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> new BlackPoints.Policy(12, 24, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BlackPoints.Policy(12, 24, List.of(3), List.of(24)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BlackPoints.Policy(12, 24, List.of(121), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new BlackPoints.Policy(12, 24, List.of(3), List.of()).warningThresholds()).isEmpty();
    }
}

package gov.itms.violation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("VF-071")
@Tag("VF-073")
class InstalmentPlanTest {

    private static final InstalmentPlan.Terms TERMS = new InstalmentPlan.Terms(500_000, 3, 12, 15);

    @Test
    @DisplayName("VF-071: monthly instalments from one month after the start; the remainder goes to the last")
    void schedule() {
        List<InstalmentPlan.Instalment> s = InstalmentPlan.schedule(1_000_001, 3, LocalDate.of(2026, 1, 31));
        assertThat(s).extracting(InstalmentPlan.Instalment::amountMinor).containsExactly(333_333L, 333_333L, 333_335L);
        assertThat(s).extracting(InstalmentPlan.Instalment::dueDate).containsExactly(LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30));
        assertThat(s).extracting(InstalmentPlan.Instalment::seq).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> InstalmentPlan.schedule(2, 3, LocalDate.of(2026, 1, 1)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("VF-071: the minimum total (BRD default 5,000) and 3 to 12 months come from the terms")
    void terms() {
        InstalmentPlan.check(TERMS, 500_000, 3);
        InstalmentPlan.check(TERMS, 900_000, 12);
        assertThatThrownBy(() -> InstalmentPlan.check(TERMS, 499_999, 6)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("INSTALMENT_TOTAL_TOO_LOW");
        assertThatThrownBy(() -> InstalmentPlan.check(TERMS, 600_000, 2)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("INSTALMENT_MONTHS_INVALID");
        assertThatThrownBy(() -> InstalmentPlan.check(TERMS, 600_000, 13)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new InstalmentPlan.Terms(0, 0, 12, 15)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstalmentPlan.Terms(0, 6, 3, 15)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstalmentPlan.Terms(0, 3, 12, 91)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("VF-073: an instalment lapses only after its due date plus the grace period; paid ones never")
    void lapse() {
        LocalDate due = LocalDate.of(2026, 3, 1);
        InstalmentPlan.Instalment open = new InstalmentPlan.Instalment(1, due, 100, UUID.randomUUID(), false, null);
        assertThat(open.lapsed(due.plusDays(15), 15)).isFalse();
        assertThat(open.lapsed(due.plusDays(16), 15)).isTrue();
        InstalmentPlan.Instalment paid = new InstalmentPlan.Instalment(1, due, 100, UUID.randomUUID(), true,
                Instant.parse("2026-03-02T00:00:00Z"));
        assertThat(paid.lapsed(due.plusDays(100), 15)).isFalse();
        assertThatThrownBy(() -> new InstalmentPlan.Instalment(1, due, 100, null, true, null))
                .isInstanceOf(IllegalArgumentException.class);

        InstalmentPlan plan = new InstalmentPlan(UUID.randomUUID(), "IP-2026-000000001", "TF-1", "NORTH", "XTS", 200,
                2, 15, InstalmentPlan.Status.ACTIVE, "u", Instant.now(), null, List.of("V-2026-000000001"),
                List.of(paid, new InstalmentPlan.Instalment(2, due.plusMonths(1), 100, UUID.randomUUID(), false,
                        null)));
        assertThat(plan.allPaid()).isFalse();
        assertThat(plan.anyLapsed(due.plusMonths(1).plusDays(15))).isFalse();
        assertThat(plan.anyLapsed(due.plusMonths(1).plusDays(16))).isTrue();
        assertThat(InstalmentPlan.itemRef("IP-2026-000000001", 2)).isEqualTo("IP-2026-000000001-02");
    }
}

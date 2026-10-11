package gov.itms.violation.application;

import static org.assertj.core.api.Assertions.assertThat;

import gov.itms.violation.domain.InstalmentPlan;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("VF-071")
class InstalmentLinesTest {

    @Test
    @DisplayName("VF-071: each instalment splits over the revenue items so that every item gets exactly its due")
    void linesAddUp() {
        Map<String, Long> byItem = new LinkedHashMap<>();
        byItem.put("FINE_REVENUE|AUTHORITY", 333_337L);
        byItem.put("FINE_REVENUE|MUNICIPALITY", 200_001L);
        byItem.put("ROAD_FUND|AUTHORITY", 7L);
        long total = 533_345L;
        Map<String, Long> received = new HashMap<>();
        long from = 0;
        for (InstalmentPlan.Instalment i : InstalmentPlan.schedule(total, 7, LocalDate.of(2026, 1, 1))) {
            List<Ports.Payables.Line> lines = InstalmentService.lines(byItem, total, from, from + i.amountMinor(),
                    "instalment " + i.seq());
            assertThat(lines.stream().mapToLong(Ports.Payables.Line::amountMinor).sum()).isEqualTo(i.amountMinor());
            assertThat(lines).allSatisfy(l -> assertThat(l.amountMinor()).isPositive());
            lines.forEach(l -> received.merge(l.financialItem() + "|" + l.beneficiary(), l.amountMinor(), Long::sum));
            from += i.amountMinor();
        }
        assertThat(received).isEqualTo(byItem);
    }

    @Test
    @DisplayName("VF-071: one revenue item takes the whole instalment")
    void singleItem() {
        List<Ports.Payables.Line> lines = InstalmentService.lines(Map.of("FINE_REVENUE|AUTHORITY", 600_000L),
                600_000, 200_000, 400_000, "instalment 2 of 3");
        assertThat(lines).singleElement().satisfies(l -> {
            assertThat(l.amountMinor()).isEqualTo(200_000);
            assertThat(l.financialItem()).isEqualTo("FINE_REVENUE");
            assertThat(l.beneficiary()).isEqualTo("AUTHORITY");
        });
    }
}

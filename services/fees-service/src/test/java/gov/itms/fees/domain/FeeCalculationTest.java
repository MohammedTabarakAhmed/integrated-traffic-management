package gov.itms.fees.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Fee methods and formulas (CC-041). */
@Tag("CC-041")
class FeeCalculationTest {

    private static Map<String, BigDecimal> q(String unit, String value) {
        return Map.of(unit, new BigDecimal(value));
    }

    @Test
    @DisplayName("fixed and per-unit fees, with minimum, maximum and half-up rounding")
    void fixedAndPerUnit() {
        assertThat(new FeeMethod.Fixed(15000).amount(Map.of())).isEqualTo(15000);
        FeeMethod.PerUnit perYear = new FeeMethod.PerUnit("YEAR", new BigDecimal("10000"), null, null);
        assertThat(perYear.amount(q("YEAR", "2"))).isEqualTo(20000);
        FeeMethod.PerUnit perTonne = new FeeMethod.PerUnit("TONNE", new BigDecimal("2500.5"), 5000L, 30000L);
        assertThat(perTonne.amount(q("TONNE", "1"))).as("minimum").isEqualTo(5000);
        assertThat(perTonne.amount(q("TONNE", "3"))).as("7501.5 rounds half up").isEqualTo(7502);
        assertThat(perTonne.amount(q("TONNE", "100"))).as("maximum").isEqualTo(30000);
        assertThatThrownBy(() -> perYear.amount(Map.of())).hasFieldOrPropertyWithValue("code", "QUANTITY_REQUIRED");
        assertThatThrownBy(() -> new FeeMethod.PerUnit("YEAR", BigDecimal.ONE, 10L, 5L))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
        assertThatThrownBy(() -> new FeeMethod.Fixed(-1)).hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
    }

    @Test
    @DisplayName("tiered fees: graduated bands and volume bands")
    void tiered() {
        List<FeeMethod.Tiered.Tier> tiers = List.of(
                new FeeMethod.Tiered.Tier(new BigDecimal("3.5"), new BigDecimal("1000")),
                new FeeMethod.Tiered.Tier(new BigDecimal("10"), new BigDecimal("2000")),
                new FeeMethod.Tiered.Tier(null, new BigDecimal("3000")));
        FeeMethod.Tiered graduated = new FeeMethod.Tiered("TONNE", FeeMethod.Tiered.Mode.GRADUATED, tiers);
        FeeMethod.Tiered volume = new FeeMethod.Tiered("TONNE", FeeMethod.Tiered.Mode.VOLUME, tiers);
        assertThat(graduated.amount(q("TONNE", "3"))).isEqualTo(3000);
        assertThat(graduated.amount(q("TONNE", "12"))).as("3.5*1000 + 6.5*2000 + 2*3000").isEqualTo(22500);
        assertThat(volume.amount(q("TONNE", "12"))).isEqualTo(36000);
        assertThat(volume.amount(q("TONNE", "3.5"))).as("upper bound inclusive").isEqualTo(3500);
        assertThat(graduated.amount(q("TONNE", "0"))).isZero();
        assertThatThrownBy(() -> new FeeMethod.Tiered("TONNE", FeeMethod.Tiered.Mode.VOLUME, List.of(
                new FeeMethod.Tiered.Tier(new BigDecimal("5"), BigDecimal.ONE),
                new FeeMethod.Tiered.Tier(new BigDecimal("4"), BigDecimal.ONE),
                new FeeMethod.Tiered.Tier(null, BigDecimal.ONE)))).hasFieldOrPropertyWithValue("code", "TIERS_INVALID");
        assertThatThrownBy(() -> new FeeMethod.Tiered("TONNE", FeeMethod.Tiered.Mode.VOLUME, List.of(
                new FeeMethod.Tiered.Tier(new BigDecimal("5"), BigDecimal.ONE))))
                .hasFieldOrPropertyWithValue("code", "TIERS_INVALID");
    }

    @Test
    @DisplayName("formulas: arithmetic, functions, quantities in lower case, safe errors")
    void formulas() {
        FeeMethod.Formula seats = new FeeMethod.Formula("max(15000, seat * 2500) + round(year * 0.5) * 100");
        assertThat(seats.amount(Map.of("SEAT", new BigDecimal("7"), "YEAR", new BigDecimal("3"))))
                .as("max(15000, 17500) + round(1.5)*100").isEqualTo(17700);
        assertThat(seats.units()).containsExactly("SEAT", "YEAR");
        assertThat(FeeFormula.parse("-(2 - 5) * 10").evaluate(Map.of())).isEqualByComparingTo("30");
        assertThat(FeeFormula.parse("ceil(10 / 3)").evaluate(Map.of())).isEqualByComparingTo("4");
        assertThat(FeeFormula.parse("floor(min(3.7, 9))").evaluate(Map.of())).isEqualByComparingTo("3");
        assertThatThrownBy(() -> FeeFormula.parse("seat * ")).hasFieldOrPropertyWithValue("code", "FORMULA_INVALID");
        assertThatThrownBy(() -> FeeFormula.parse("exec(1)")).hasFieldOrPropertyWithValue("code", "FORMULA_INVALID");
        assertThatThrownBy(() -> FeeFormula.parse("Runtime.getRuntime()"))
                .hasFieldOrPropertyWithValue("code", "FORMULA_INVALID");
        assertThatThrownBy(() -> FeeFormula.parse("(1 + 2")).hasFieldOrPropertyWithValue("code", "FORMULA_INVALID");
        assertThatThrownBy(() -> FeeFormula.parse("round(1, 2)")).hasFieldOrPropertyWithValue("code", "FORMULA_INVALID");
        assertThatThrownBy(() -> FeeFormula.parse("x".repeat(501))).hasFieldOrPropertyWithValue("code", "FORMULA_INVALID");
        assertThatThrownBy(() -> FeeFormula.parse("10 / seat").evaluate(Map.of("seat", BigDecimal.ZERO)))
                .hasFieldOrPropertyWithValue("code", "FORMULA_DIVISION_BY_ZERO");
        assertThatThrownBy(() -> new FeeMethod.Formula("0 - seat").amount(q("SEAT", "2")))
                .hasFieldOrPropertyWithValue("code", "FORMULA_NEGATIVE");
        assertThatThrownBy(() -> new FeeMethod.Formula("seat * 10").amount(Map.of()))
                .hasFieldOrPropertyWithValue("code", "QUANTITY_REQUIRED");
    }
}

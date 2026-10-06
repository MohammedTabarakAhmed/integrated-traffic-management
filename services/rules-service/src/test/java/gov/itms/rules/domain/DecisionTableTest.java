package gov.itms.rules.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-050")
class DecisionTableTest {

    static DecisionTable minimumAge(DecisionTable.HitPolicy policy) {
        return new DecisionTable(
                List.of(new DecisionTable.Column("category", DecisionTable.Type.TEXT),
                        new DecisionTable.Column("age", DecisionTable.Type.NUMBER)),
                List.of(new DecisionTable.Column("eligible", DecisionTable.Type.BOOLEAN)),
                policy,
                List.of(new DecisionTable.Rule(Map.of(
                                "category", new DecisionTable.Condition(DecisionTable.Op.IN, List.of("B", "A1")),
                                "age", new DecisionTable.Condition(DecisionTable.Op.GE, List.of(18))),
                                Map.of("eligible", true), "car or light motorcycle from 18"),
                        new DecisionTable.Rule(Map.of(
                                "category", new DecisionTable.Condition(DecisionTable.Op.EQ, List.of("C")),
                                "age", new DecisionTable.Condition(DecisionTable.Op.GE, List.of(21))),
                                Map.of("eligible", true), null)),
                Map.of("eligible", false));
    }

    @Test
    @DisplayName("CC-050: the first matching rule decides; with no match the defaults apply")
    void firstHitAndDefaults() {
        DecisionTable t = minimumAge(DecisionTable.HitPolicy.FIRST);
        Map<String, Object> adult = t.checkInputs(Map.of("category", "B", "age", 18));
        assertThat(t.matches(adult)).containsExactly(0);
        assertThat(t.outputsOf(t.matches(adult))).containsExactly(Map.of("eligible", true));

        Map<String, Object> young = t.checkInputs(Map.of("category", "C", "age", 20));
        assertThat(t.matches(young)).isEmpty();
        assertThat(t.outputsOf(List.of())).containsExactly(Map.of("eligible", false));
    }

    @Test
    @DisplayName("CC-050: BETWEEN is inclusive, NOT_IN excludes, decimals compare by value")
    void operators() {
        DecisionTable t = new DecisionTable(
                List.of(new DecisionTable.Column("points", DecisionTable.Type.NUMBER),
                        new DecisionTable.Column("region", DecisionTable.Type.TEXT)),
                List.of(new DecisionTable.Column("band", DecisionTable.Type.TEXT)),
                DecisionTable.HitPolicy.COLLECT,
                List.of(new DecisionTable.Rule(Map.of("points",
                                new DecisionTable.Condition(DecisionTable.Op.BETWEEN, List.of(new BigDecimal("1.0"), 12))),
                                Map.of("band", "LOW"), null),
                        new DecisionTable.Rule(Map.of("region",
                                new DecisionTable.Condition(DecisionTable.Op.NOT_IN, List.of("NORTH"))),
                                Map.of("band", "SOUTHERN"), null)),
                null);
        assertThat(t.matches(t.checkInputs(Map.of("points", 12.00, "region", "SOUTH")))).containsExactly(0, 1);
        assertThat(t.matches(t.checkInputs(Map.of("points", 1, "region", "NORTH")))).containsExactly(0);
        assertThat(t.matches(t.checkInputs(Map.of("points", 13, "region", "NORTH")))).isEmpty();
    }

    @Test
    @DisplayName("CC-050: UNIQUE refuses two matching rules")
    void uniqueRefusesOverlap() {
        DecisionTable t = new DecisionTable(
                List.of(new DecisionTable.Column("age", DecisionTable.Type.NUMBER)),
                List.of(new DecisionTable.Column("ok", DecisionTable.Type.BOOLEAN)),
                DecisionTable.HitPolicy.UNIQUE,
                List.of(new DecisionTable.Rule(Map.of("age", new DecisionTable.Condition(DecisionTable.Op.GE,
                                List.of(18))), Map.of("ok", true), null),
                        new DecisionTable.Rule(Map.of("age", new DecisionTable.Condition(DecisionTable.Op.GE,
                                List.of(21))), Map.of("ok", true), null)),
                null);
        assertThat(t.matches(t.checkInputs(Map.of("age", 19)))).containsExactly(0);
        assertThatThrownBy(() -> t.matches(t.checkInputs(Map.of("age", 30))))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_NOT_UNIQUE");
    }

    @Test
    @DisplayName("CC-050: inputs are checked against the declared types; a missing input fails its conditions")
    void inputTypes() {
        DecisionTable t = minimumAge(DecisionTable.HitPolicy.FIRST);
        assertThatThrownBy(() -> t.checkInputs(Map.of("colour", "red")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_INPUT_UNKNOWN");
        assertThatThrownBy(() -> t.checkInputs(Map.of("age", "eighteen")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_INPUT_INVALID");
        Map<String, Object> partial = new HashMap<>();
        partial.put("category", "B");
        partial.put("age", null);
        assertThat(t.matches(t.checkInputs(partial))).isEmpty();
    }

    @Test
    @DisplayName("CC-050: inconsistent tables are refused when submitted")
    void invalidTables() {
        List<DecisionTable.Column> in = List.of(new DecisionTable.Column("age", DecisionTable.Type.NUMBER));
        List<DecisionTable.Column> out = List.of(new DecisionTable.Column("ok", DecisionTable.Type.BOOLEAN));
        assertThatThrownBy(() -> new DecisionTable(in, out, DecisionTable.HitPolicy.FIRST,
                List.of(new DecisionTable.Rule(Map.of("height", DecisionTable.Condition.ANY), Map.of("ok", true),
                        null)), null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_TABLE_INVALID");
        assertThatThrownBy(() -> new DecisionTable(in, out, DecisionTable.HitPolicy.FIRST,
                List.of(new DecisionTable.Rule(Map.of(), Map.of("ok", "yes"), null)), null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_TABLE_INVALID");
        assertThatThrownBy(() -> new DecisionTable.Condition(DecisionTable.Op.BETWEEN, List.of(1)))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_TABLE_INVALID");
        assertThatThrownBy(() -> new DecisionTable.Column("Bad-Name", DecisionTable.Type.TEXT))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_TABLE_INVALID");
    }
}

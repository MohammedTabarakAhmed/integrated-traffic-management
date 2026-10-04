package gov.itms.refdata.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ItemDataTest {

    private static final BilingualName NAME = new BilingualName("اسم", "Name");
    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);

    @Test
    @Tag("CC-012")
    @DisplayName("CC-012: names are required in both languages")
    void bothLanguagesRequired() {
        assertThatThrownBy(() -> new BilingualName("", "Name"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NAME_REQUIRED"));
        assertThatThrownBy(() -> new BilingualName("اسم", null))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NAME_REQUIRED"));
        assertThatThrownBy(() -> new BilingualName("x".repeat(201), "Name"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NAME_TOO_LONG"));
        assertThat(new BilingualName("  اسم ", " Name ")).isEqualTo(new BilingualName("اسم", "Name"));
    }

    @Test
    @Tag("CC-110")
    @DisplayName("CC-110: effective dates are inclusive and the end date may be open")
    void effectivePeriod() {
        ItemData open = new ItemData(NAME, true, JAN_1, null, null, null, 0);
        ItemData closed = new ItemData(NAME, true, JAN_1, LocalDate.of(2026, 6, 30), null, null, 0);

        assertThat(open.isEffectiveOn(JAN_1.minusDays(1))).isFalse();
        assertThat(open.isEffectiveOn(JAN_1)).isTrue();
        assertThat(open.isEffectiveOn(LocalDate.of(2099, 1, 1))).isTrue();
        assertThat(closed.isEffectiveOn(LocalDate.of(2026, 6, 30))).isTrue();
        assertThat(closed.isEffectiveOn(LocalDate.of(2026, 7, 1))).isFalse();
    }

    @Test
    @Tag("DAT-004")
    @DisplayName("DAT-004: end date before start date is rejected")
    void endBeforeStartRejected() {
        assertThatThrownBy(() -> new ItemData(NAME, true, JAN_1, JAN_1.minusDays(1), null, null, 0))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("EFFECTIVE_DATES_INVALID"));
        assertThatThrownBy(() -> new ItemData(NAME, true, null, null, null, null, 0))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("EFFECTIVE_FROM_REQUIRED"));
        assertThatThrownBy(() -> new ItemData(NAME, true, JAN_1, null, null, null, -1))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("SORT_ORDER_INVALID"));
    }

    @Test
    @Tag("CC-110")
    @DisplayName("CC-110: external codes per partner are kept, sorted and immutable")
    void externalCodes() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("INSURER_HUB", "EXP-INS");
        input.put("COURTS", "07");
        ItemData d = new ItemData(NAME, true, JAN_1, null, null, input, 0);

        assertThat(d.externalCodes()).containsExactly(Map.entry("COURTS", "07"), Map.entry("INSURER_HUB", "EXP-INS"));
        assertThatThrownBy(() -> d.externalCodes().put("X", "Y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @Tag("DAT-004")
    @ValueSource(strings = {"lower", "WITH SPACE", "TOO_LONG_CODE_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", ""})
    @DisplayName("DAT-004: malformed codes are rejected")
    void invalidCodes(String code) {
        assertThatThrownBy(() -> Codes.require("itemCode", code))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CODE"));
    }

    @Test
    @Tag("DAT-004")
    @DisplayName("DAT-004: malformed partner codes are rejected")
    void invalidExternalCodes() {
        assertThatThrownBy(() -> new ItemData(NAME, true, JAN_1, null, null, Map.of("bad partner", "1"), 0))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ItemData(NAME, true, JAN_1, null, null, Map.of("COURTS", "<script>"), 0))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("EXTERNAL_CODE_INVALID"));
    }
}

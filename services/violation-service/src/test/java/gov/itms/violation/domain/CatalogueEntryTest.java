package gov.itms.violation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("VF-001")
public class CatalogueEntryTest {

    public static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    /** A plain entry: fine, points, optional repeat rule, national unless {@code region}. */
    public static CatalogueEntry entry(String code, String region, long fine, int points, Integer repeatDays,
                                       Long repeatFine, LocalDate from, LocalDate to) {
        return new CatalogueEntry(code, 1, region, "Art. 10", "مخالفة", "Violation " + code, fine, points, 0, 0,
                false, CatalogueEntry.AppliesTo.DRIVER, List.of(), repeatDays, repeatFine, "FINE_REVENUE",
                "AUTHORITY", from, to, "u-admin", AT);
    }

    /** Speeding with bands (0,20] 300/0, (20,40] 600/4, (40,∞) 1000/8 km/h over the limit. */
    public static CatalogueEntry speeding(String code) {
        return new CatalogueEntry(code, 1, null, "Art. 52", "سرعة", "Speeding", 0, 0, 0, 0, false,
                CatalogueEntry.AppliesTo.BOTH, List.of(new CatalogueEntry.SpeedBand(40, null, 100_000, 8),
                        new CatalogueEntry.SpeedBand(0, 20, 30_000, 0),
                        new CatalogueEntry.SpeedBand(20, 40, 60_000, 4)),
                null, null, "FINE_REVENUE", "AUTHORITY", FROM, null, "u-admin", AT);
    }

    @Test
    @Tag("VF-004")
    @DisplayName("VF-004: bands are sorted and contiguous; the band of the speed over the limit sets fine and points")
    void speedBands() {
        CatalogueEntry e = speeding("SPEEDING");
        assertThat(e.speedBands()).extracting(CatalogueEntry.SpeedBand::fromKmh).containsExactly(0, 20, 40);
        assertThat(e.charge(20, false)).isEqualTo(new CatalogueEntry.Charge(30_000, 0, false));
        assertThat(e.charge(21, false)).isEqualTo(new CatalogueEntry.Charge(60_000, 4, false));
        assertThat(e.charge(40, false).blackPoints()).isEqualTo(4);
        assertThat(e.charge(120, false)).isEqualTo(new CatalogueEntry.Charge(100_000, 8, false));
        assertThatThrownBy(() -> e.charge(0, false)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SPEED_NOT_IN_BANDS");
        assertThatThrownBy(() -> e.charge(null, false)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SPEED_REQUIRED");
        assertThatThrownBy(() -> new CatalogueEntry("GAP", 1, null, "Art. 1", "x", "x", 0, 0, 0, 0, false,
                CatalogueEntry.AppliesTo.DRIVER, List.of(new CatalogueEntry.SpeedBand(0, 20, 1, 0),
                        new CatalogueEntry.SpeedBand(25, null, 2, 0)), null, null, "FINE", "AUTHORITY", FROM, null,
                "u", AT)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("SPEED_BANDS_INVALID");
        assertThatThrownBy(() -> new CatalogueEntry("OPEN", 1, null, "Art. 1", "x", "x", 0, 0, 0, 0, false,
                CatalogueEntry.AppliesTo.DRIVER, List.of(new CatalogueEntry.SpeedBand(0, null, 1, 0),
                        new CatalogueEntry.SpeedBand(20, null, 2, 0)), null, null, "FINE", "AUTHORITY", FROM, null,
                "u", AT)).as("an open band must be last").isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SPEED_BANDS_INVALID");
    }

    @Test
    @Tag("VF-003")
    @DisplayName("VF-003: a repeat uses the repeat fine (never less than the normal fine); the rule needs both parts")
    void repeatFine() {
        CatalogueEntry e = entry("RED_LIGHT", null, 80_000, 6, 365, 160_000L, FROM, null);
        assertThat(e.charge(null, false)).isEqualTo(new CatalogueEntry.Charge(80_000, 6, false));
        assertThat(e.charge(null, true)).isEqualTo(new CatalogueEntry.Charge(160_000, 6, true));
        assertThat(entry("X", null, 80_000, 6, 30, 50_000L, FROM, null).charge(null, true).fineMinor())
                .isEqualTo(80_000);
        assertThat(entry("NO_REPEAT", null, 500, 0, null, null, FROM, null).charge(null, true))
                .isEqualTo(new CatalogueEntry.Charge(500, 0, false));
        assertThatThrownBy(() -> entry("X", null, 1, 0, 30, null, FROM, null)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("REPEAT_RULE_INVALID");
        assertThatThrownBy(() -> entry("X", null, 1, 0, 0, 1L, FROM, null)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("REPEAT_RULE_INVALID");
    }

    @Test
    @Tag("VF-002")
    @DisplayName("VF-002: a version is in force from its start to its inclusive end")
    void inForce() {
        CatalogueEntry e = entry("PARKING", "NORTH", 20_000, 0, null, null, FROM, FROM.plusDays(9));
        assertThat(e.inForce(FROM.minusDays(1))).isFalse();
        assertThat(e.inForce(FROM)).isTrue();
        assertThat(e.inForce(FROM.plusDays(9))).isTrue();
        assertThat(e.inForce(FROM.plusDays(10))).isFalse();
        assertThat(entry("PARKING", null, 20_000, 0, null, null, FROM, null).inForce(FROM.plusYears(30))).isTrue();
        assertThatThrownBy(() -> entry("PARKING", null, 1, 0, null, null, FROM, FROM.minusDays(1)))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
    }

    @Test
    @DisplayName("VF-001: codes, amounts and texts are checked")
    void validation() {
        assertThatThrownBy(() -> entry("bad code", null, 1, 0, null, null, FROM, null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> entry("X", null, -1, 0, null, null, FROM, null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> entry("X", null, 1, 101, null, null, FROM, null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> entry("X", "north", 1, 0, null, null, FROM, null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new CatalogueEntry.SpeedBand(10, 10, 1, 0)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("FIELD_INVALID");
    }
}

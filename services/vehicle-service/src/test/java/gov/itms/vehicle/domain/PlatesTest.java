package gov.itms.vehicle.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("VL-031")
class PlatesTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    private static Plates.Plate plate(Plates.Status status) {
        return new Plates.Plate(UUID.randomUUID(), "S1", 1234, "A 1234", status, null, null, NOW);
    }

    @Test
    @DisplayName("VL-031: every allowed status transition, and nothing else")
    void transitions() {
        Map<Plates.Status, Set<Plates.Status>> allowed = Map.of(
                Plates.Status.AVAILABLE, EnumSet.of(Plates.Status.RESERVED, Plates.Status.ASSIGNED,
                        Plates.Status.WITHDRAWN),
                Plates.Status.RESERVED, EnumSet.of(Plates.Status.ASSIGNED, Plates.Status.AVAILABLE,
                        Plates.Status.CANCELLED),
                Plates.Status.ASSIGNED, EnumSet.of(Plates.Status.RETAINED, Plates.Status.RETURNED, Plates.Status.LOST,
                        Plates.Status.CANCELLED),
                Plates.Status.RETAINED, EnumSet.of(Plates.Status.ASSIGNED, Plates.Status.RETURNED,
                        Plates.Status.CANCELLED),
                Plates.Status.WITHDRAWN, EnumSet.of(Plates.Status.ASSIGNED, Plates.Status.RETAINED),
                Plates.Status.RETURNED, EnumSet.noneOf(Plates.Status.class),
                Plates.Status.CANCELLED, EnumSet.noneOf(Plates.Status.class),
                Plates.Status.LOST, EnumSet.noneOf(Plates.Status.class));
        UUID vehicle = UUID.randomUUID();
        for (Plates.Status from : Plates.Status.values()) {
            for (Plates.Status to : Plates.Status.values()) {
                if (allowed.get(from).contains(to)) {
                    Plates.Plate moved = plate(from).moveTo(to, vehicle, "TF-1", NOW.plusSeconds(1));
                    assertThat(moved.status()).isEqualTo(to);
                    assertThat(moved.vehicleId()).isEqualTo(vehicle);
                    assertThat(moved.changedAt()).isEqualTo(NOW.plusSeconds(1));
                } else {
                    assertThatThrownBy(() -> plate(from).moveTo(to, vehicle, "TF-1", NOW)).as(from + " → " + to)
                            .isInstanceOf(DomainException.class).extracting("code")
                            .isEqualTo("PLATE_STATUS_INVALID");
                }
            }
        }
    }

    @Test
    @Tag("VL-030")
    @Tag("VL-032")
    @DisplayName("VL-030, VL-032: premium numbers follow the series pattern; display joins prefix and number")
    void premiumAndDisplay() {
        Plates.Series s = new Plates.Series("DXB_PRIVATE_A", "NORTH", "PRIVATE", "WHITE", "STANDARD", "A", 1,
                99_999, "^(\\d)\\1+$|^\\d{1,2}$", true);
        assertThat(s.premium(7777)).isTrue();
        assertThat(s.premium(12)).isTrue();
        assertThat(s.premium(1234)).isFalse();
        assertThat(s.display(1234)).isEqualTo("A 1234");
        assertThat(new Plates.Series("S2", "NORTH", "PRIVATE", "WHITE", "STANDARD", "", 1, 9, null, true)
                .display(5)).isEqualTo("5");
        assertThat(new Plates.Series("S2", "NORTH", "PRIVATE", "WHITE", "STANDARD", "", 1, 9, null, true)
                .premium(1)).isFalse();
    }

    @Test
    @Tag("VL-030")
    @DisplayName("VL-030: ranges, prefixes and premium patterns are checked")
    void seriesValidation() {
        assertThatThrownBy(() -> new Plates.Series("S", "NORTH", "PRIVATE", "WHITE", "STANDARD", "A", 10, 9, null,
                true)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("SERIES_RANGE_INVALID");
        assertThatThrownBy(() -> new Plates.Series("S", "NORTH", "PRIVATE", "WHITE", "STANDARD", "A", 1,
                100_000_000L, null, true)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SERIES_RANGE_INVALID");
        assertThatThrownBy(() -> new Plates.Series("S", "NORTH", "PRIVATE", "WHITE", "STANDARD", "ABCDEF", 1, 9,
                null, true)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Plates.Series("S", "NORTH", "PRIVATE", "WHITE", "STANDARD", "A", 1, 9, "(",
                true)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
    }
}

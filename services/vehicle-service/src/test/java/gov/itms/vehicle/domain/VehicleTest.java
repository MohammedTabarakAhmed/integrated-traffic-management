package gov.itms.vehicle.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("VL-001")
public class VehicleTest {

    public static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    public static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    public static Vehicle.Spec spec(String category, int modelYear) {
        return new Vehicle.Spec("ENG-1", "TOYOTA", "COROLLA", modelYear, "SEDAN", List.of("WHITE"), "PETROL", "LEFT",
                5, 1200, 1700, 2, 4, category);
    }

    @Test
    @Tag("VL-010")
    @Tag("VL-011")
    @DisplayName("VL-010, VL-011: UNREGISTERED → REGISTERED with plate and expiry; renewal moves the expiry forward")
    void lifecycle() {
        Vehicle v = Vehicle.create(UUID.randomUUID(), "1HGCM82633A004352", spec("PRIVATE", 2024), "TF-1", "NORTH",
                NOW);
        assertThat(v.status()).isEqualTo(Vehicle.Status.UNREGISTERED);
        assertThat(v.registrationValid(TODAY)).isFalse();
        assertThatThrownBy(() -> v.renewed(TODAY.plusYears(1))).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("NOT_REGISTERED");
        UUID plate = UUID.randomUUID();
        v.registered(plate, "A 1", TODAY.plusYears(1).minusDays(1));
        assertThat(v.status()).isEqualTo(Vehicle.Status.REGISTERED);
        assertThat(v.plateNumber()).isEqualTo("A 1");
        assertThat(v.registrationValid(TODAY.plusYears(1).minusDays(1))).isTrue();
        assertThat(v.registrationValid(TODAY.plusYears(1))).as("expired the day after").isFalse();
        assertThatThrownBy(() -> v.registered(plate, "A 2", TODAY)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("ALREADY_REGISTERED");
        v.renewed(TODAY.plusYears(2));
        assertThat(v.registrationExpiry()).isEqualTo(TODAY.plusYears(2));
        assertThatThrownBy(() -> v.renewed(TODAY.plusYears(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("VL-023, VL-041: age counts whole years from the model year, never negative")
    void age() {
        Vehicle v = Vehicle.create(UUID.randomUUID(), "1HGCM82633A004352", spec("PRIVATE", 2023), "TF-1", "NORTH",
                NOW);
        assertThat(v.ageYears(LocalDate.of(2026, 1, 1))).isEqualTo(3);
        assertThat(v.ageYears(LocalDate.of(2025, 12, 31))).isEqualTo(2);
        Vehicle next = Vehicle.create(UUID.randomUUID(), "1HGCM82633A004352", spec("PRIVATE", 2027), "TF-1",
                "NORTH", NOW);
        assertThat(next.ageYears(TODAY)).isZero();
    }

    @Test
    @Tag("VL-004")
    @DisplayName("VL-001, VL-004: technical data and codes are checked")
    void validation() {
        assertThatThrownBy(() -> spec("private", 2024)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Vehicle.Spec("ENG-1", "TOYOTA", "COROLLA", 2024, "SEDAN", List.of(), "PETROL",
                "LEFT", 5, 1200, 1700, 2, 4, "PRIVATE")).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Vehicle.Spec("ENG-1", "TOYOTA", "COROLLA", 2024, "SEDAN", List.of("WHITE"),
                "PETROL", "MIDDLE", 5, 1200, 1700, 2, 4, "PRIVATE")).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Vehicle.Spec("ENG-1", "TOYOTA", "COROLLA", 2024, "SEDAN", List.of("WHITE"),
                "PETROL", "LEFT", 5, 1800, 1700, 2, 4, "PRIVATE")).as("gross below empty weight")
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VEHICLE_DATA_INVALID");
        assertThatThrownBy(() -> Vehicle.create(UUID.randomUUID(), "1HGCM82633A004352", spec("PRIVATE", 2024),
                "tf 1", "NORTH", NOW)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("FIELD_INVALID");
    }
}

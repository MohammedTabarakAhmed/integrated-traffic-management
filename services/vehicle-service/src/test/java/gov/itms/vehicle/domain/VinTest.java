package gov.itms.vehicle.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("VL-003")
class VinTest {

    private static final List<String> NORTH_AMERICA = List.of("1", "2", "3", "4", "5");

    @Test
    @DisplayName("VL-003: published VINs pass the ISO 3779 check digit (known answers, X for 10)")
    void knownAnswers() {
        for (String vin : List.of("1M8GDM9AXKP042788", "11111111111111111", "5GZCZ43D13S812715",
                "1HGCM82633A004352")) {
            assertThat(Vin.check(vin, NORTH_AMERICA)).isEqualTo(vin);
        }
        assertThat(Vin.checkDigit("1M8GDM9AXKP042788")).isEqualTo('X');
        assertThat(Vin.checkDigit("1HGCM82633A004352")).isEqualTo('3');
    }

    @Test
    @DisplayName("VL-003: a wrong check digit is refused where the region uses one; elsewhere only the format counts")
    void regions() {
        assertThatThrownBy(() -> Vin.check("1M8GDM9A1KP042788", NORTH_AMERICA)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("VIN_CHECK_DIGIT");
        assertThat(Vin.check("WDB1234567A123456", NORTH_AMERICA)).as("European VIN, no check digit")
                .isEqualTo("WDB1234567A123456");
        assertThatThrownBy(() -> Vin.check("WDB1234567A123456", List.of())).as("empty list checks every VIN")
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VIN_CHECK_DIGIT");
        assertThat(Vin.check(" 1m8gdm9axkp042788 ", NORTH_AMERICA)).as("trimmed and upper-cased")
                .isEqualTo("1M8GDM9AXKP042788");
    }

    @Test
    @DisplayName("VL-003: 17 characters without I, O and Q")
    void format() {
        for (String bad : new String[] {null, "", "1M8GDM9AXKP04278", "1M8GDM9AXKP0427888", "1M8GDM9AXKP04278I",
                "1M8GDM9AXKP04278O", "1M8GDM9AXKP04278Q", "1M8GDM9AXKP04278-"}) {
            assertThatThrownBy(() -> Vin.check(bad, List.of())).as(String.valueOf(bad))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VIN_INVALID");
        }
    }
}

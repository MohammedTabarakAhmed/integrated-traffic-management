package gov.itms.trafficfile.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Ranges and formats of the traffic-file settings (NFR-042, Q-022, Q-057). */
@Tag("NFR-042")
class TrafficFilePropertiesTest {

    private static final TrafficFileProperties.Register REGISTER = new TrafficFileProperties.Register(
            URI.create("http://register"), Duration.ofSeconds(3), Map.of("M", "MALE"), Map.of());

    @Test
    void validSettings() {
        var p = new TrafficFileProperties("^[0-9]{15}$", "TF", 10, REGISTER);
        assertThat(p.nationalIdRegex().matcher("784199012345671").matches()).isTrue();
    }

    @Test
    void fileNumbersCarryALuhnCheckDigit() {
        assertThat(JdbcTrafficFileRepository.luhn("7992739871")).isEqualTo(3);
        assertThat(JdbcTrafficFileRepository.luhn("0000000001")).isEqualTo(8);
        assertThat(JdbcTrafficFileRepository.luhn("0000000000")).isZero();
    }

    @Test
    void invalidSettingsStopTheService() {
        assertThatThrownBy(() -> new TrafficFileProperties("[", "TF", 10, REGISTER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrafficFileProperties("^.+$", "tf", 10, REGISTER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrafficFileProperties("^.+$", "TF", 5, REGISTER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrafficFileProperties("^.+$", "ABCDEF", 15, REGISTER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrafficFileProperties("^.+$", "TF", 10, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrafficFileProperties.Register(URI.create("http://r"), Duration.ofMillis(100),
                Map.of("M", "MALE"), Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrafficFileProperties.Register(URI.create("http://r"), Duration.ofSeconds(3),
                Map.of(), Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
}

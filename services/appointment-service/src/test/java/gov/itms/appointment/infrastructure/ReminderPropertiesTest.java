package gov.itms.appointment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.appointment.infrastructure.AppointmentServiceConfiguration.ReminderProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("NFR-042")
class ReminderPropertiesTest {

    @Test
    @DisplayName("NFR-042: reminder lead 1 h-7 days, interval 1 min-1 h, batch 1-1000")
    void ranges() {
        assertThat(new ReminderProperties(true, Duration.ofHours(1), Duration.ofMinutes(1), 1).lead())
                .isEqualTo(Duration.ofHours(1));
        assertThat(new ReminderProperties(true, Duration.ofDays(7), Duration.ofHours(1), 1000).batchSize())
                .isEqualTo(1000);
        assertThatThrownBy(() -> new ReminderProperties(true, Duration.ofMinutes(59), Duration.ofMinutes(5), 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lead");
        assertThatThrownBy(() -> new ReminderProperties(true, Duration.ofDays(8), Duration.ofMinutes(5), 10))
                .hasMessageContaining("lead");
        assertThatThrownBy(() -> new ReminderProperties(true, Duration.ofHours(24), Duration.ofSeconds(59), 10))
                .hasMessageContaining("interval");
        assertThatThrownBy(() -> new ReminderProperties(true, Duration.ofHours(24), Duration.ofMinutes(61), 10))
                .hasMessageContaining("interval");
        assertThatThrownBy(() -> new ReminderProperties(true, Duration.ofHours(24), Duration.ofMinutes(5), 0))
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new ReminderProperties(true, Duration.ofHours(24), Duration.ofMinutes(5), 1001))
                .hasMessageContaining("batch-size");
    }
}

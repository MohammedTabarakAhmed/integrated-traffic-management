package gov.itms.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("NFR-042")
@Tag("CC-072")
class NotificationPropertiesTest {

    private static NotificationProperties props(Map<String, URI> gateways, Duration timeout, int attempts,
                                                Duration retry, List<Integer> offsets, Duration interval, int batch) {
        return new NotificationProperties(gateways, timeout, attempts, retry, offsets, interval, batch, null);
    }

    private static NotificationProperties defaults() {
        return props(Map.of("sms", URI.create("http://gw/sms")), Duration.ofSeconds(5), 5, Duration.ofSeconds(30),
                List.of(30, 7, 1), Duration.ofSeconds(5), 50);
    }

    @Test
    @DisplayName("NFR-042: BRD default offsets 30/7/1 are accepted; gateway keys are case-insensitive")
    void defaultsAccepted() {
        NotificationProperties p = defaults();
        assertThat(p.reminderOffsetDays()).containsExactly(30, 7, 1);
        assertThat(p.gateways()).containsKey("SMS");
    }

    @Test
    @DisplayName("NFR-042: every setting is range-checked")
    void ranges() {
        Map<String, URI> gw = Map.of();
        List<Integer> offsets = List.of(30, 7, 1);
        assertThatThrownBy(() -> props(Map.of("FAX", URI.create("http://x")), Duration.ofSeconds(5), 5,
                Duration.ofSeconds(30), offsets, Duration.ofSeconds(5), 50))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gateways");
        assertThatThrownBy(() -> props(gw, Duration.ofMillis(499), 5, Duration.ofSeconds(30), offsets,
                Duration.ofSeconds(5), 50)).hasMessageContaining("gateway-timeout");
        assertThatThrownBy(() -> props(gw, Duration.ofSeconds(31), 5, Duration.ofSeconds(30), offsets,
                Duration.ofSeconds(5), 50)).hasMessageContaining("gateway-timeout");
        assertThatThrownBy(() -> props(gw, Duration.ofSeconds(5), 0, Duration.ofSeconds(30), offsets,
                Duration.ofSeconds(5), 50)).hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> props(gw, Duration.ofSeconds(5), 21, Duration.ofSeconds(30), offsets,
                Duration.ofSeconds(5), 50)).hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> props(gw, Duration.ofSeconds(5), 5, Duration.ofMillis(999), offsets,
                Duration.ofSeconds(5), 50)).hasMessageContaining("retry-delay");
        assertThatThrownBy(() -> props(gw, Duration.ofSeconds(5), 5, Duration.ofSeconds(30), List.of(),
                Duration.ofSeconds(5), 50)).hasMessageContaining("reminder-offset-days");
        assertThatThrownBy(() -> props(gw, Duration.ofSeconds(5), 5, Duration.ofSeconds(30), List.of(7, 7),
                Duration.ofSeconds(5), 50)).hasMessageContaining("reminder-offset-days");
        assertThatThrownBy(() -> props(gw, Duration.ofSeconds(5), 5, Duration.ofSeconds(30), List.of(366),
                Duration.ofSeconds(5), 50)).hasMessageContaining("reminder-offset-days");
        assertThatThrownBy(() -> props(gw, Duration.ofSeconds(5), 5, Duration.ofSeconds(30), offsets,
                Duration.ofMinutes(11), 50)).hasMessageContaining("dispatch-interval");
        assertThatThrownBy(() -> props(gw, Duration.ofSeconds(5), 5, Duration.ofSeconds(30), offsets,
                Duration.ofSeconds(5), 501)).hasMessageContaining("batch-size");
    }
}

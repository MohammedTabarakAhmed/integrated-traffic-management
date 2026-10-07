package gov.itms.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.payment.domain.Payment;
import gov.itms.payment.infrastructure.PaymentServiceConfiguration.PaymentProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("NFR-042")
@Tag("FN-005")
class PaymentPropertiesTest {

    private static final PaymentProperties.Gateway GATEWAY = new PaymentProperties.Gateway(
            URI.create("http://gw:8098"), Duration.ofSeconds(5), "http://itms:9080", "k1,k2");

    private static PaymentProperties props(Duration inquiry, Duration window, Duration interval, String zone,
                                           PaymentProperties.Gateway gateway) {
        return new PaymentProperties(null, inquiry, window, zone, interval, gateway);
    }

    @Test
    @Tag("FN-001")
    @DisplayName("FN-001, FN-005: defaults are the BRD values (30-minute window) and the default methods")
    void defaults() {
        PaymentProperties p = props(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofMinutes(1), "UTC",
                GATEWAY);
        assertThat(p.acceptedMethods()).contains(Payment.Method.GATEWAY_CARD, Payment.Method.CASH)
                .doesNotContain(Payment.Method.CHEQUE);
        assertThat(new PaymentProperties(List.of(Payment.Method.CHEQUE), Duration.ofMinutes(5), Duration.ofMinutes(30),
                "UTC", Duration.ofMinutes(1), GATEWAY).acceptedMethods()).containsExactly(Payment.Method.CHEQUE);
    }

    @Test
    @DisplayName("NFR-042: inquiry 1-30 min, window 5-120 min and after the inquiry, interval 10 s-10 min, real zone")
    void ranges() {
        Duration m5 = Duration.ofMinutes(5);
        Duration m30 = Duration.ofMinutes(30);
        Duration m1 = Duration.ofMinutes(1);
        assertThatThrownBy(() -> props(Duration.ofSeconds(59), m30, m1, "UTC", GATEWAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inquiry-after");
        assertThatThrownBy(() -> props(Duration.ofMinutes(31), Duration.ofMinutes(60), m1, "UTC", GATEWAY))
                .hasMessageContaining("inquiry-after");
        assertThatThrownBy(() -> props(m5, Duration.ofMinutes(121), m1, "UTC", GATEWAY))
                .hasMessageContaining("resolution-window");
        assertThatThrownBy(() -> props(Duration.ofMinutes(10), Duration.ofMinutes(10), m1, "UTC", GATEWAY))
                .hasMessageContaining("resolution-window");
        assertThatThrownBy(() -> props(m5, m30, Duration.ofSeconds(9), "UTC", GATEWAY))
                .hasMessageContaining("job-interval");
        assertThatThrownBy(() -> props(m5, m30, m1, "Mars/Olympus", GATEWAY)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @Tag("SEC-004")
    @DisplayName("SEC-004: the gateway addresses and the callback keys (a secret file) are required")
    void gatewayRequired() {
        Duration m5 = Duration.ofMinutes(5);
        Duration m30 = Duration.ofMinutes(30);
        Duration m1 = Duration.ofMinutes(1);
        assertThatThrownBy(() -> props(m5, m30, m1, "UTC", new PaymentProperties.Gateway(null, Duration.ofSeconds(5),
                "http://itms", "k"))).hasMessageContaining("base-url");
        assertThatThrownBy(() -> props(m5, m30, m1, "UTC", new PaymentProperties.Gateway(URI.create("http://gw"),
                Duration.ofSeconds(5), "http://itms", " "))).hasMessageContaining("secret file");
    }
}

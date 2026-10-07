package gov.itms.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FN-004")
class PaymentTest {

    private static final Instant NOW = PayableTest.NOW;

    private static Payment pending(Payment.Method method) {
        return Payment.start(UUID.randomUUID(), UUID.randomUUID(), method, 66000, "XTS", "PORTAL", "NORTH", "c-1",
                NOW);
    }

    @Test
    @DisplayName("FN-004: a matching SUCCESS confirms once; anything later is refused")
    void confirm() {
        Payment p = pending(Payment.Method.GATEWAY_CARD);
        p.sessionOpened("sess-1");
        assertThat(p.confirm("gw-1", 66000, "XTS", NOW.plusSeconds(30))).isTrue();
        assertThat(p.status()).isEqualTo(Payment.Status.CONFIRMED);
        assertThat(p.gatewayTxnId()).isEqualTo("gw-1");
        assertThat(p.confirmedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThatThrownBy(() -> p.confirm("gw-1", 66000, "XTS", NOW)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("PAYMENT_NOT_PENDING");
        assertThatThrownBy(() -> p.fail("gw-1")).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("PAYMENT_NOT_PENDING");
    }

    @Test
    @DisplayName("FN-004: an amount or currency mismatch goes to EXCEPTION, never CONFIRMED")
    void mismatch() {
        Payment amount = pending(Payment.Method.GATEWAY_CARD);
        assertThat(amount.confirm("gw-1", 65999, "XTS", NOW)).isFalse();
        assertThat(amount.status()).isEqualTo(Payment.Status.EXCEPTION);
        assertThat(amount.exceptionReason()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(amount.confirmedAt()).isNull();
        Payment currency = pending(Payment.Method.E_PAYMENT);
        assertThat(currency.confirm("gw-2", 66000, "USD", NOW)).isFalse();
        assertThat(currency.status()).isEqualTo(Payment.Status.EXCEPTION);
    }

    @Test
    @Tag("FN-005")
    @DisplayName("FN-005: an unresolved PENDING payment goes to EXCEPTION with the elapsed time")
    void unresolved() {
        Payment p = pending(Payment.Method.GATEWAY_CARD);
        p.inquired(NOW.plus(Duration.ofMinutes(5)));
        assertThat(p.lastInquiryAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        p.unresolved(NOW.plus(Duration.ofMinutes(31)));
        assertThat(p.status()).isEqualTo(Payment.Status.EXCEPTION);
        assertThat(p.exceptionReason()).isEqualTo("UNRESOLVED_AFTER_31_MIN");
    }

    @Test
    @Tag("FN-001")
    @DisplayName("FN-001: counter methods are confirmed by the cashier; gateway methods cannot be")
    void counter() {
        Payment cash = pending(Payment.Method.CASH);
        cash.confirmAtCounter("TILL-7", NOW);
        assertThat(cash.status()).isEqualTo(Payment.Status.CONFIRMED);
        assertThat(cash.gatewayTxnId()).isEqualTo("TILL-7");
        assertThatThrownBy(() -> pending(Payment.Method.GATEWAY_CARD).confirmAtCounter("X", NOW))
                .isInstanceOf(IllegalStateException.class);
        Payment failed = pending(Payment.Method.GATEWAY_CARD);
        failed.fail("gw-9");
        assertThat(failed.status()).isEqualTo(Payment.Status.FAILED);
        assertThat(Payment.GATEWAY_METHODS).doesNotContainAnyElementsOf(Payment.COUNTER_METHODS);
    }
}

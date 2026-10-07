package gov.itms.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FN-004")
public class HmacCallbackVerifierTest {

    private static final UUID PAYMENT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** The ICD-03 signature: HMAC-SHA256 over paymentId|txn|status|amount|currency|paidAt, lower-case hex. */
    public static String sign(String key, UUID paymentId, String txn, String status, long amount, String currency,
                              String paidAt) {
        String message = paymentId + "|" + txn + "|" + status + "|" + amount + "|" + currency + "|"
                + (paidAt == null ? "" : paidAt);
        return HexFormat.of().formatHex(GatewayAdapters.HmacCallbackVerifier.hmac(
                key.getBytes(StandardCharsets.UTF_8), message));
    }

    @Test
    @DisplayName("FN-004: a known HMAC-SHA256 value (RFC 4231 test case 2) and a signed callback are accepted")
    void knownAnswerAndValid() {
        assertThat(HexFormat.of().formatHex(GatewayAdapters.HmacCallbackVerifier.hmac(
                "Jefe".getBytes(StandardCharsets.UTF_8), "what do ya want for nothing?")))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
        GatewayAdapters.HmacCallbackVerifier v = new GatewayAdapters.HmacCallbackVerifier(List.of("key-1"));
        String sig = sign("key-1", PAYMENT, "gw-1", "SUCCESS", 66000, "XTS", "2026-10-06T08:00:00Z");
        assertThat(v.valid(PAYMENT, "gw-1", "SUCCESS", 66000, "XTS", "2026-10-06T08:00:00Z", sig)).isTrue();
        assertThat(v.valid(PAYMENT, "gw-1", "SUCCESS", 66000, "XTS", "2026-10-06T08:00:00Z", sig.toUpperCase()))
                .as("hex case does not matter").isTrue();
        assertThat(v.valid(PAYMENT, "gw-1", "SUCCESS", 66000, "XTS", null,
                sign("key-1", PAYMENT, "gw-1", "SUCCESS", 66000, "XTS", null))).isTrue();
    }

    @Test
    @DisplayName("FN-004: any change to a signed field, or another key, is refused")
    void tampering() {
        GatewayAdapters.HmacCallbackVerifier v = new GatewayAdapters.HmacCallbackVerifier(List.of("key-1"));
        String sig = sign("key-1", PAYMENT, "gw-1", "SUCCESS", 66000, "XTS", "t");
        assertThat(v.valid(PAYMENT, "gw-1", "SUCCESS", 66001, "XTS", "t", sig)).isFalse();
        assertThat(v.valid(PAYMENT, "gw-1", "FAILED", 66000, "XTS", "t", sig)).isFalse();
        assertThat(v.valid(PAYMENT, "gw-2", "SUCCESS", 66000, "XTS", "t", sig)).isFalse();
        assertThat(v.valid(PAYMENT, "gw-1", "SUCCESS", 66000, "USD", "t", sig)).isFalse();
        assertThat(v.valid(UUID.randomUUID(), "gw-1", "SUCCESS", 66000, "XTS", "t", sig)).isFalse();
        assertThat(v.valid(PAYMENT, "gw-1", "SUCCESS", 66000, "XTS", "t",
                sign("other-key", PAYMENT, "gw-1", "SUCCESS", 66000, "XTS", "t"))).isFalse();
    }

    @Test
    @Tag("SEC-004")
    @DisplayName("FN-004, SEC-004: during a key rotation both keys verify; after it the old one does not")
    void rotation() {
        String oldSig = sign("old-key", PAYMENT, "gw-1", "SUCCESS", 1, "XTS", "t");
        String newSig = sign("new-key", PAYMENT, "gw-1", "SUCCESS", 1, "XTS", "t");
        GatewayAdapters.HmacCallbackVerifier during = new GatewayAdapters.HmacCallbackVerifier(
                List.of("new-key", "old-key"));
        assertThat(during.valid(PAYMENT, "gw-1", "SUCCESS", 1, "XTS", "t", oldSig)).isTrue();
        assertThat(during.valid(PAYMENT, "gw-1", "SUCCESS", 1, "XTS", "t", newSig)).isTrue();
        GatewayAdapters.HmacCallbackVerifier after = new GatewayAdapters.HmacCallbackVerifier(List.of("new-key"));
        assertThat(after.valid(PAYMENT, "gw-1", "SUCCESS", 1, "XTS", "t", oldSig)).isFalse();
    }

    @Test
    @DisplayName("FN-004: missing, short, long or non-hex signatures are refused without an exception")
    void badHex() {
        GatewayAdapters.HmacCallbackVerifier v = new GatewayAdapters.HmacCallbackVerifier(List.of("key-1"));
        for (String bad : new String[] {null, "", "abc", "z".repeat(64), "0".repeat(63), "0".repeat(65),
                "0".repeat(62) + " 0"}) {
            assertThat(v.valid(PAYMENT, "gw-1", "SUCCESS", 1, "XTS", "t", bad)).as(String.valueOf(bad)).isFalse();
        }
    }
}

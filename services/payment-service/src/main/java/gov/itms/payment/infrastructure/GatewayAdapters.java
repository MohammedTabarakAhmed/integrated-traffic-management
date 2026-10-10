package gov.itms.payment.infrastructure;

import gov.itms.payment.application.Ports;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** ICD-03 adapters: the gateway's session and status API, and the callback signature check. */
final class GatewayAdapters {

    private GatewayAdapters() {
    }

    /**
     * {@code POST /v1/payment-sessions} and {@code GET /v1/payments/{merchantRef}/status}. ITMS never sees card data:
     * the customer pays on the gateway's pages (PCI scope stays with the gateway).
     */
    static class HttpPaymentGateway implements Ports.PaymentGateway {

        private static final Logger LOG = LoggerFactory.getLogger(HttpPaymentGateway.class);
        private final RestClient client;
        private final String callbackBaseUrl;

        HttpPaymentGateway(RestClient client, String callbackBaseUrl) {
            this.client = client;
            this.callbackBaseUrl = callbackBaseUrl;
        }

        record SessionRequest(String merchantRef, long amountMinor, String currency, String returnUrl,
                              String callbackUrl, String itemsSummary) {
        }

        record SessionAnswer(String sessionId, String redirectUrl) {
        }

        record StatusBody(String status, String gatewayTxnId, Long amountMinor, String currency, String paidAt) {
        }

        record RefundRequest(String merchantRef, String gatewayTxnId, long amountMinor, String currency) { }

        record RefundAnswer(String refundRef) { }

        @Override
        public Session openSession(UUID paymentId, long amountMinor, String currency, String returnUrl,
                                   String summary) {
            try {
                SessionAnswer a = client.post().uri("/v1/payment-sessions")
                        .body(new SessionRequest(paymentId.toString(), amountMinor, currency, returnUrl,
                                callbackBaseUrl + "/v1/payments/" + paymentId + "/callbacks", summary))
                        .retrieve().body(SessionAnswer.class);
                if (a == null || a.sessionId() == null || a.redirectUrl() == null) {
                    throw new Ports.GatewayUnavailableException("gateway gave no session", null);
                }
                return new Session(a.sessionId(), a.redirectUrl());
            } catch (RestClientException e) {
                LOG.warn("Payment gateway did not open a session for {}: {}", paymentId, e.getClass().getSimpleName());
                throw new Ports.GatewayUnavailableException("payment gateway unavailable", e);
            }
        }

        @Override
        public StatusAnswer status(UUID paymentId) {
            try {
                StatusBody b = client.get().uri("/v1/payments/{ref}/status", paymentId.toString()).retrieve()
                        .body(StatusBody.class);
                if (b == null || b.status() == null) {
                    return new StatusAnswer(RemoteStatus.UNKNOWN, null, 0, null, null);
                }
                RemoteStatus s = switch (b.status()) {
                    case "SUCCESS" -> RemoteStatus.SUCCESS;
                    case "FAILED" -> RemoteStatus.FAILED;
                    case "PENDING" -> RemoteStatus.PENDING;
                    default -> RemoteStatus.UNKNOWN;
                };
                return new StatusAnswer(s, b.gatewayTxnId(), b.amountMinor() == null ? 0 : b.amountMinor(),
                        b.currency(), b.paidAt() == null ? null : Instant.parse(b.paidAt()));
            } catch (RuntimeException e) {
                LOG.warn("Payment gateway status inquiry for {} failed: {}", paymentId, e.getClass().getSimpleName());
                throw new Ports.GatewayUnavailableException("payment gateway unavailable", e);
            }
        }

        @Override
        public String refund(UUID paymentId, String gatewayTxnId, String refundId, long amountMinor, String currency) {
            try {
                RefundAnswer answer = client.post().uri("/v1/refunds")
                        .header("Idempotency-Key", refundId)
                        .body(new RefundRequest(paymentId.toString(), gatewayTxnId, amountMinor, currency))
                        .retrieve().body(RefundAnswer.class);
                if (answer == null || answer.refundRef() == null || answer.refundRef().isBlank()) {
                    throw new Ports.GatewayUnavailableException("gateway gave no refund reference", null);
                }
                return answer.refundRef();
            } catch (RestClientException e) {
                LOG.warn("Refund {} for payment {} was not confirmed: {}", refundId, paymentId,
                        e.getClass().getSimpleName());
                throw new Ports.GatewayUnavailableException("payment gateway refund is unavailable", e);
            }
        }
    }

    /**
     * HMAC-SHA256 over {@code paymentId|gatewayTxnId|status|amountMinor|currency|paidAt}, hex, compared in constant
     * time. Several keys may be configured during a rotation; any one matching is enough (ICD-03).
     */
    static class HmacCallbackVerifier implements Ports.CallbackVerifier {

        private final List<byte[]> keys;

        HmacCallbackVerifier(List<String> keys) {
            this.keys = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toList();
        }

        @Override
        public boolean valid(UUID paymentId, String gatewayTxnId, String status, long amountMinor, String currency,
                             String paidAt, String signature) {
            if (signature == null || !signature.matches("^[0-9a-fA-F]{64}$")) {
                return false;
            }
            String message = paymentId + "|" + gatewayTxnId + "|" + status + "|" + amountMinor + "|" + currency + "|"
                    + (paidAt == null ? "" : paidAt);
            byte[] given = HexFormat.of().parseHex(signature.toLowerCase(java.util.Locale.ROOT));
            for (byte[] key : keys) {
                if (MessageDigest.isEqual(hmac(key, message), given)) {
                    return true;
                }
            }
            return false;
        }

        static byte[] hmac(byte[] key, String message) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

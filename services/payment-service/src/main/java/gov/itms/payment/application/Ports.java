package gov.itms.payment.application;

import gov.itms.payment.domain.Basket;
import gov.itms.payment.domain.Payable;
import gov.itms.payment.domain.Payment;
import gov.itms.payment.domain.Receipt;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ports of payment-service. */
public final class Ports {

    private Ports() {
    }

    public interface PaymentRepository {

        void insertPayable(Payable payable);

        Optional<Payable> findPayable(UUID id);

        /** Locks the payables in id order (no deadlocks between concurrent payments). */
        List<Payable> lockPayables(List<UUID> ids);

        Optional<Payable> findPayableByItem(Payable.ItemType type, String itemRef);

        /** Payables of a traffic file, newest first, optionally only in {@code status} (ADR-067 d). */
        default List<Payable> payablesOfFile(String trafficFileNumber, Payable.Status status, int limit) {
            return List.of();
        }

        void savePayableStatus(Payable payable);

        boolean payableHeld(UUID id);

        void placePayableHold(UUID id, String sourceRef, String reason, String actor, Instant at);

        boolean liftPayableHold(UUID id, String sourceRef, String reason, String actor, Instant at);

        void insertBasket(Basket basket);

        Optional<Basket> findBasket(UUID id);

        void saveBasketStatus(Basket basket);

        void insertPayment(Payment payment);

        Optional<Payment> findPayment(UUID id);

        Optional<Payment> findPaymentForUpdate(UUID id);

        void updatePayment(Payment payment);

        boolean pendingPaymentExists(UUID basketId);

        /** PENDING gateway payments created before {@code olderThan}; locked, skipping rows others hold. */
        List<Payment> pendingForInquiry(Instant olderThan, int limit);

        /** Records a gateway callback (FN-004); the outcome is ACCEPTED, DUPLICATE or REJECTED. */
        void logCallback(UUID paymentId, String gatewayTxnId, String status, String outcome, Instant at);

        /** Next number of the gapless sequence for a region, channel and year (FN-022); locks the sequence row. */
        long nextReceiptNumber(String region, String channel, int year);

        void insertReceipt(Receipt receipt);

        /** Persists one retryable general-ledger posting per receipt line (FN-013). */
        void insertLedgerPostings(Receipt receipt);

        Optional<UUID> openTillForUpdate(String cashierId, String region);

        void recordTillPayment(Payment payment, Receipt receipt);

        Optional<Receipt> findReceipt(String number);

        Optional<Receipt> findReceiptForUpdate(String number);

        Optional<Receipt> findReceiptByPayment(UUID paymentId);

        void saveReceiptCancellation(Receipt receipt);

        /** Receipt enquiry (FN-014); {@code null} filters mean any. */
        record ReceiptQuery(String trafficFileNumber, String payerId, LocalDate from, LocalDate to, Long amountMinor,
                            String channel, String financialItem, String region, int limit) {
        }

        List<Receipt> searchReceipts(ReceiptQuery query);
    }

    /** The payment gateway / national e-payment platform (ICD-03). */
    public interface PaymentGateway {

        record Session(String sessionId, String redirectUrl) {
        }

        enum RemoteStatus { SUCCESS, FAILED, PENDING, UNKNOWN }

        record StatusAnswer(RemoteStatus status, String gatewayTxnId, long amountMinor, String currency,
                            Instant paidAt) {
        }

        Session openSession(UUID paymentId, long amountMinor, String currency, String returnUrl, String summary);

        StatusAnswer status(UUID paymentId);

        /** Requests an idempotent refund to the original online method (FN-012). */
        default String refund(UUID paymentId, String gatewayTxnId, String refundId, long amountMinor, String currency) {
            throw new GatewayUnavailableException("gateway refund is not supported", null);
        }
    }

    public static class GatewayUnavailableException extends RuntimeException {
        public GatewayUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The signed-in customer's traffic file (ADR-067 d), from traffic-file-service by the national id in their token.
     * Empty when they have no file; throws {@link TrafficFilesUnavailableException} when it cannot answer.
     */
    public interface OwnFile {
        Optional<String> fileNumber();
    }

    public static class TrafficFilesUnavailableException extends RuntimeException {
        public TrafficFilesUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Checks the gateway's signature on a callback (ICD-03: HMAC with a rotated key). */
    public interface CallbackVerifier {
        boolean valid(UUID paymentId, String gatewayTxnId, String status, long amountMinor, String currency,
                      String paidAt, String signature);
    }
}

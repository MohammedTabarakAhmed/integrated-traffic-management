package gov.itms.payment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One payment of a basket (FN-001, FN-002, FN-004, FN-005; ADR-054).
 *
 * <p>Gateway methods: PENDING (session opened) → CONFIRMED (first valid SUCCESS callback or status inquiry) or FAILED;
 * a PENDING payment unresolved after the resolution window becomes EXCEPTION for the finance queue; an amount mismatch
 * is an EXCEPTION too. Counter methods (cash, card terminal, cheque) are CONFIRMED when the cashier records them.
 * A confirmed payment has exactly one receipt.
 */
public final class Payment {

    public enum Method { GATEWAY_CARD, E_PAYMENT, MOBILE_WALLET, BANK_TRANSFER, DIRECT_DEBIT, CASH, CARD_TERMINAL,
        CHEQUE, COLLECTION_PARTNER }

    /** Methods confirmed by the gateway's callback; the others are recorded by a cashier or partner. */
    public static final Set<Method> GATEWAY_METHODS = Set.of(Method.GATEWAY_CARD, Method.E_PAYMENT,
            Method.MOBILE_WALLET);
    public static final Set<Method> COUNTER_METHODS = Set.of(Method.CASH, Method.CARD_TERMINAL, Method.CHEQUE);

    public enum Status { PENDING, CONFIRMED, FAILED, EXCEPTION }

    private final UUID id;
    private final UUID basketId;
    private final Method method;
    private final long amountMinor;
    private final String currency;
    private final String channel;
    private final String region;
    private final String payerId;
    private final Instant createdAt;
    private Status status;
    private String gatewaySessionId;
    private String gatewayTxnId;
    private Instant confirmedAt;
    private String exceptionReason;
    private Instant lastInquiryAt;

    private Payment(UUID id, UUID basketId, Method method, long amountMinor, String currency, String channel,
                    String region, String payerId, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.basketId = Objects.requireNonNull(basketId);
        this.method = Objects.requireNonNull(method);
        this.amountMinor = amountMinor;
        this.currency = Objects.requireNonNull(currency);
        this.channel = Objects.requireNonNull(channel);
        this.region = Objects.requireNonNull(region);
        this.payerId = Objects.requireNonNull(payerId);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static Payment start(UUID id, UUID basketId, Method method, long amountMinor, String currency,
                                String channel, String region, String payerId, Instant now) {
        Payment p = new Payment(id, basketId, method, amountMinor, currency, channel, region, payerId, now);
        p.status = Status.PENDING;
        return p;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public static Payment restore(UUID id, UUID basketId, Method method, long amountMinor, String currency,
                                  String channel, String region, String payerId, Instant createdAt, Status status,
                                  String sessionId, String txnId, Instant confirmedAt, String exceptionReason,
                                  Instant lastInquiryAt) {
        Payment p = new Payment(id, basketId, method, amountMinor, currency, channel, region, payerId, createdAt);
        p.status = status;
        p.gatewaySessionId = sessionId;
        p.gatewayTxnId = txnId;
        p.confirmedAt = confirmedAt;
        p.exceptionReason = exceptionReason;
        p.lastInquiryAt = lastInquiryAt;
        return p;
    }

    public void sessionOpened(String sessionId) {
        this.gatewaySessionId = sessionId;
    }

    /**
     * A SUCCESS from the gateway (callback or inquiry). The amount and currency must match what was asked; otherwise
     * the payment goes to EXCEPTION and nothing is confirmed (ICD-03).
     */
    public boolean confirm(String txnId, long paidMinor, String paidCurrency, Instant at) {
        requirePending();
        gatewayTxnId = txnId;
        if (paidMinor != amountMinor || !currency.equals(paidCurrency)) {
            status = Status.EXCEPTION;
            exceptionReason = "AMOUNT_MISMATCH";
            return false;
        }
        status = Status.CONFIRMED;
        confirmedAt = at;
        return true;
    }

    /** A counter payment recorded by a cashier is confirmed at once. */
    public void confirmAtCounter(String reference, Instant at) {
        requirePending();
        if (!COUNTER_METHODS.contains(method)) {
            throw new IllegalStateException("not a counter method");
        }
        gatewayTxnId = reference;
        status = Status.CONFIRMED;
        confirmedAt = at;
    }

    public void fail(String txnId) {
        requirePending();
        gatewayTxnId = txnId;
        status = Status.FAILED;
    }

    /** Unresolved within the window (FN-005): the finance queue takes over. */
    public void unresolved(Instant now) {
        requirePending();
        status = Status.EXCEPTION;
        exceptionReason = "UNRESOLVED_AFTER_" + Duration.between(createdAt, now).toMinutes() + "_MIN";
    }

    public void inquired(Instant now) {
        lastInquiryAt = now;
    }

    private void requirePending() {
        if (status != Status.PENDING) {
            throw DomainException.conflict("PAYMENT_NOT_PENDING", "the payment is " + status, List.of("FN-004"),
                    status.name());
        }
    }

    public UUID id() {
        return id;
    }

    public UUID basketId() {
        return basketId;
    }

    public Method method() {
        return method;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public String channel() {
        return channel;
    }

    public String region() {
        return region;
    }

    public String payerId() {
        return payerId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    public String gatewaySessionId() {
        return gatewaySessionId;
    }

    public String gatewayTxnId() {
        return gatewayTxnId;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    public String exceptionReason() {
        return exceptionReason;
    }

    public Instant lastInquiryAt() {
        return lastInquiryAt;
    }
}

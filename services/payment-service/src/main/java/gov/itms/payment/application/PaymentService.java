package gov.itms.payment.application;

import gov.itms.payment.domain.Basket;
import gov.itms.payment.domain.DomainException;
import gov.itms.payment.domain.Payable;
import gov.itms.payment.domain.Payment;
import gov.itms.payment.domain.Receipt;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payments (FN-001..FN-005, FN-010, FN-011, FN-014, FN-022; ADR-054).
 * <ul>
 *   <li>owning services register payables ({@value #PAYABLE_REGISTER}) and cancel open ones;</li>
 *   <li>a customer (or a cashier for them) puts open payables in a basket and pays it: online through the gateway
 *       (a session, then the gateway's callback) or at a counter ({@value #COLLECT}: cash, card terminal, cheque if
 *       accepted) — FN-001, FN-002;</li>
 *   <li>callbacks are verified (signature, amount, currency) and processed idempotently: the first SUCCESS confirms
 *       the payment and issues exactly one receipt; any repeat is logged as a duplicate and answered with the same
 *       receipt (FN-004, UAT-4);</li>
 *   <li>a job asks the gateway about PENDING payments and moves unresolved ones to EXCEPTION within the configured
 *       window, 30 minutes by default (FN-005);</li>
 *   <li>receipts are itemised by financial item, typed (FN-010), numbered from a gapless sequence per region, channel
 *       and year (FN-022), searchable (FN-014), and cancellable on the same business day by a supervisor
 *       ({@value #RECEIPT_CANCEL}, FN-011).</li>
 * </ul>
 */
public class PaymentService {

    public static final String PAYABLE_REGISTER = "payment.payable.register";
    public static final String PAYABLE_HOLD = "payment.payable.hold";
    public static final String PAY_FOR_OTHERS = "payment.basket.create";
    public static final String COLLECT = "payment.collect";
    public static final String CALLBACK = "payment.gateway.callback";
    public static final String RECEIPT_READ = "payment.receipt.read";
    public static final String RECEIPT_CANCEL = "payment.receipt.cancel";
    public static final String EXCEPTION_READ = "payment.exception.read";
    private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

    /** Settings (configuration, NFR-042). */
    public record Settings(Set<Payment.Method> acceptedMethods, Duration inquiryAfter, Duration resolutionWindow,
                           ZoneId businessZone) {
        public Settings {
            acceptedMethods = Set.copyOf(acceptedMethods);
        }
    }

    /** What a payment start returns: the payment, and for the gateway the page to send the customer to. */
    public record Started(Payment payment, String redirectUrl, Receipt receipt) {
    }

    /** Outcome of a callback (FN-004). */
    public record CallbackOutcome(Payment payment, Receipt receipt, boolean duplicate) {
    }

    private final Ports.PaymentRepository repo;
    private final Ports.PaymentGateway gateway;
    private final Ports.CallbackVerifier verifier;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Settings settings;
    private final Clock clock;

    public PaymentService(Ports.PaymentRepository repo, Ports.PaymentGateway gateway, Ports.CallbackVerifier verifier,
                          AuditRecorder audit, DomainEventRecorder events, Settings settings, Clock clock) {
        this.repo = repo;
        this.gateway = gateway;
        this.verifier = verifier;
        this.audit = audit;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    // ---- payables (FN-002)

    @Transactional
    public Payable register(Payable.ItemType type, String itemRef, String trafficFileNumber, String region,
                            String currency, List<Payable.Line> lines, Caller caller) {
        channelOf(caller);
        require(caller.grants().allowsInRegion(PAYABLE_REGISTER, region));
        repo.findPayableByItem(type, itemRef).filter(p -> p.status() != Payable.Status.CANCELLED).ifPresent(p -> {
            throw DomainException.conflict("PAYABLE_EXISTS", "item " + itemRef + " is already payable",
                    List.of("FN-002"), itemRef);
        });
        Payable p = Payable.register(UUID.randomUUID(), type, itemRef, trafficFileNumber, region, currency, lines,
                caller.actor().id(), clock.instant());
        repo.insertPayable(p);
        audit.record("PAYABLE_REGISTERED", "Payable", p.id().toString(), null, payableSnapshot(p), null);
        return p;
    }

    @Transactional
    public Payable cancelPayable(UUID id, String reason, Caller caller) {
        channelOf(caller);
        Payable p = repo.lockPayables(List.of(id)).stream().findFirst().orElseThrow(() -> notFound("PAYABLE", id));
        require(caller.grants().allowsInRegion(PAYABLE_REGISTER, p.region()));
        p.cancel();
        repo.savePayableStatus(p);
        audit.record("PAYABLE_CANCELLED", "Payable", id.toString(), null, payableSnapshot(p), reason);
        return p;
    }

    @Transactional(readOnly = true)
    public Payable payable(UUID id) {
        return repo.findPayable(id).orElseThrow(() -> notFound("PAYABLE", id));
    }

    @Transactional
    public void placePayableHold(UUID id, String sourceRef, String reason, Caller caller) {
        channelOf(caller);
        Payable payable = repo.lockPayables(List.of(id)).stream().findFirst()
                .orElseThrow(() -> notFound("PAYABLE", id));
        require(caller.grants().allowsInRegion(PAYABLE_HOLD, payable.region()));
        if (sourceRef == null || !sourceRef.matches("^[A-Z0-9][A-Z0-9:_-]{0,79}$")
                || reason == null || reason.isBlank() || reason.length() > 500) {
            throw DomainException.validation("PAYABLE_HOLD_INVALID", "a source reference and reason are required",
                    List.of("VF-052"));
        }
        if (payable.status() != Payable.Status.OPEN) {
            throw DomainException.conflict("PAYABLE_NOT_OPEN", "only an open payable can be paused",
                    List.of("VF-052"));
        }
        repo.placePayableHold(id, sourceRef, reason.strip(), caller.actor().id(), clock.instant());
        audit.record("PAYABLE_PAUSED", "Payable", id.toString(), null, Map.of("sourceRef", sourceRef), reason);
    }

    @Transactional
    public void liftPayableHold(UUID id, String sourceRef, String reason, Caller caller) {
        channelOf(caller);
        Payable payable = repo.lockPayables(List.of(id)).stream().findFirst()
                .orElseThrow(() -> notFound("PAYABLE", id));
        require(caller.grants().allowsInRegion(PAYABLE_HOLD, payable.region()));
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw DomainException.validation("REASON_INVALID", "a reason is required", List.of("VF-052"));
        }
        if (!repo.liftPayableHold(id, sourceRef, reason.strip(), caller.actor().id(), clock.instant())) {
            throw DomainException.conflict("PAYABLE_HOLD_NOT_ACTIVE", "the payable has no matching active hold",
                    List.of("VF-052"));
        }
        audit.record("PAYABLE_RESUMED", "Payable", id.toString(), Map.of("sourceRef", sourceRef),
                Map.of("active", false), reason);
    }

    // ---- baskets and payments (FN-001, FN-002)

    @Transactional
    public Basket basket(List<UUID> payableIds, String region, Caller caller) {
        channelOf(caller);
        List<Payable> items = new ArrayList<>();
        for (UUID id : payableIds) {
            Payable item = repo.findPayable(id).orElseThrow(() -> notFound("PAYABLE", id));
            if (repo.payableHeld(id)) {
                throw DomainException.conflict("PAYABLE_PAUSED", "item " + item.itemRef() + " is under objection",
                        List.of("VF-052"));
            }
            items.add(item);
        }
        Basket b = Basket.of(UUID.randomUUID(), items, region, caller.actor().id(), clock.instant());
        repo.insertBasket(b);
        audit.record("BASKET_CREATED", "Basket", b.id().toString(), null,
                Map.of("items", b.payableIds().size(), "total", b.totalMinor(), "currency", b.currency()), null);
        return b;
    }

    @Transactional(readOnly = true)
    public Basket getBasket(UUID id, Caller caller) {
        Basket b = repo.findBasket(id).orElseThrow(() -> notFound("BASKET", id));
        require(b.createdBy().equals(caller.actor().id()) || caller.grants().holds(RECEIPT_READ)
                || caller.grants().holds(COLLECT));
        return b;
    }

    /**
     * Starts paying a basket. Gateway methods open a session and wait for the callback; counter methods need
     * {@value #COLLECT} and are confirmed at once with a receipt.
     */
    @Transactional
    public Started pay(UUID basketId, Payment.Method method, String returnUrl, String counterReference,
                       Caller caller) {
        String channel = channelOf(caller);
        if (!settings.acceptedMethods().contains(method)) {
            throw DomainException.validation("METHOD_NOT_ACCEPTED", "this payment method is not accepted",
                    List.of("FN-001"), method.name());
        }
        Basket b = repo.findBasket(basketId).orElseThrow(() -> notFound("BASKET", basketId));
        b.requireOpen();
        if (b.payableIds().stream().anyMatch(repo::payableHeld)) {
            throw DomainException.conflict("PAYABLE_PAUSED", "a basket item is under objection", List.of("VF-052"));
        }
        boolean counter = Payment.COUNTER_METHODS.contains(method);
        if (counter) {
            require(caller.grants().allowsInRegion(COLLECT, b.region()));
        } else if (!b.createdBy().equals(caller.actor().id())) {
            require(caller.grants().holds(PAY_FOR_OTHERS));
        }
        // permission first, so a caller without it learns nothing about tills (FN-020)
        if (counter && repo.openTillForUpdate(caller.actor().id(), b.region()).isEmpty()) {
            throw DomainException.conflict("TILL_NOT_OPEN", "open a till before collecting counter payments",
                    List.of("FN-020"));
        }
        if (!counter && !Payment.GATEWAY_METHODS.contains(method)) {
            throw DomainException.validation("METHOD_NOT_ACCEPTED", "this method is settled by partner integration",
                    List.of("FN-001", "FN-006"), method.name());
        }
        if (repo.pendingPaymentExists(basketId)) {
            throw DomainException.conflict("PAYMENT_IN_PROGRESS", "a payment of this basket is in progress",
                    List.of("FN-004"));
        }
        Instant now = clock.instant();
        Payment p = Payment.start(UUID.randomUUID(), basketId, method, b.totalMinor(), b.currency(), channel,
                b.region(), caller.actor().id(), now);
        if (counter) {
            if (counterReference == null || !counterReference.matches("^[A-Za-z0-9-]{1,60}$")) {
                throw DomainException.validation("FIELD_INVALID", "give the counter reference (till slip, cheque "
                        + "number or terminal reference)", List.of("FN-001"), "counterReference");
            }
            // the cashier takes the money only for items still open
            List<Payable> locked = repo.lockPayables(b.payableIds());
            locked.forEach(Payable::requireOpen);
            if (locked.stream().anyMatch(item -> repo.payableHeld(item.id()))) {
                throw DomainException.conflict("PAYABLE_PAUSED", "a basket item is under objection",
                        List.of("VF-052"));
            }
            repo.insertPayment(p);
            p.confirmAtCounter(counterReference, now);
            Receipt r = settle(p, b, caller.actor().id());
            if (r == null) {
                return new Started(repo.findPayment(p.id()).orElse(p), null, null);
            }
            audit.record("PAYMENT_COLLECTED", "Payment", p.id().toString(), null, paymentSnapshot(p),
                    r.number());
            return new Started(p, null, r);
        }
        repo.insertPayment(p);
        Ports.PaymentGateway.Session session = gateway.openSession(p.id(), p.amountMinor(), p.currency(), returnUrl,
                b.payableIds().size() + " item(s)");
        p.sessionOpened(session.sessionId());
        repo.updatePayment(p);
        audit.record("PAYMENT_STARTED", "Payment", p.id().toString(), null, paymentSnapshot(p), null);
        return new Started(p, session.redirectUrl(), null);
    }

    @Transactional(readOnly = true)
    public Payment payment(UUID id, Caller caller) {
        Payment p = repo.findPayment(id).orElseThrow(() -> notFound("PAYMENT", id));
        require(p.payerId().equals(caller.actor().id()) || caller.grants().holds(RECEIPT_READ)
                || caller.grants().holds(EXCEPTION_READ));
        return p;
    }

    // ---- callbacks (FN-004, UAT-4)

    /**
     * The gateway's confirmation. Verified first (signature, then amount and currency); one row lock per payment
     * serialises concurrent duplicates; the first SUCCESS confirms and issues the one receipt, every repeat is
     * logged DUPLICATE and answered with the same state.
     */
    @Transactional(noRollbackFor = DomainException.class)
    public CallbackOutcome callback(UUID paymentId, String gatewayTxnId, String status, long amountMinor,
                                    String currency, String paidAt, String signature, Caller caller) {
        require(caller.grants().holds(CALLBACK));
        Instant now = clock.instant();
        if (!verifier.valid(paymentId, gatewayTxnId, status, amountMinor, currency, paidAt, signature)) {
            audit.record("PAYMENT_CALLBACK_REJECTED", "Payment", paymentId.toString(), null,
                    Map.of("reason", "SIGNATURE_INVALID", "gatewayTxnId", gatewayTxnId), null);
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SIGNATURE_INVALID",
                    "the callback signature is not valid", List.of("FN-004", "SEC-017"));
        }
        Payment p = repo.findPaymentForUpdate(paymentId).orElseThrow(() -> notFound("PAYMENT", paymentId));
        if (p.status() != Payment.Status.PENDING) {
            repo.logCallback(paymentId, gatewayTxnId, status, "DUPLICATE", now);
            audit.record("PAYMENT_CALLBACK_DUPLICATE", "Payment", paymentId.toString(), null,
                    Map.of("gatewayTxnId", gatewayTxnId, "status", status, "paymentStatus", p.status().name()),
                    null);
            return new CallbackOutcome(p, repo.findReceiptByPayment(paymentId).orElse(null), true);
        }
        repo.logCallback(paymentId, gatewayTxnId, status, "ACCEPTED", now);
        if ("SUCCESS".equals(status)) {
            Receipt r = confirm(p, gatewayTxnId, amountMinor, currency, now, "gateway-callback");
            return new CallbackOutcome(repo.findPayment(paymentId).orElse(p), r, false);
        }
        p.fail(gatewayTxnId);
        repo.updatePayment(p);
        audit.record("PAYMENT_FAILED", "Payment", paymentId.toString(), null, paymentSnapshot(p), null);
        events.record("itms.payment.failed", paymentId.toString(), paymentSnapshot(p));
        return new CallbackOutcome(p, null, false);
    }

    private Receipt confirm(Payment p, String txnId, long amountMinor, String currency, Instant now, String by) {
        if (!p.confirm(txnId, amountMinor, currency, now)) {
            repo.updatePayment(p);
            raiseException(p, "amount or currency differs from the payment");
            return null;
        }
        Basket b = repo.findBasket(p.basketId()).orElseThrow();
        return settle(p, b, by);
    }

    /** Marks the payables paid and issues the receipt; an item paid meanwhile sends the payment to EXCEPTION. */
    private Receipt settle(Payment p, Basket b, String by) {
        List<Payable> items = repo.lockPayables(b.payableIds());
        for (Payable item : items) {
            if (repo.payableHeld(item.id())) {
                repo.updatePayment(Payment.restore(p.id(), p.basketId(), p.method(), p.amountMinor(), p.currency(),
                        p.channel(), p.region(), p.payerId(), p.createdAt(), Payment.Status.EXCEPTION,
                        p.gatewaySessionId(), p.gatewayTxnId(), p.confirmedAt(), "ITEM_PAUSED:" + item.itemRef(),
                        p.lastInquiryAt()));
                raiseException(p, "item " + item.itemRef() + " is paused");
                return null;
            }
            if (item.status() != Payable.Status.OPEN) {
                repo.updatePayment(Payment.restore(p.id(), p.basketId(), p.method(), p.amountMinor(), p.currency(),
                        p.channel(), p.region(), p.payerId(), p.createdAt(), Payment.Status.EXCEPTION,
                        p.gatewaySessionId(), p.gatewayTxnId(), p.confirmedAt(), "ITEM_NOT_OPEN:" + item.itemRef(),
                        p.lastInquiryAt()));
                raiseException(p, "item " + item.itemRef() + " is " + item.status());
                return null;
            }
        }
        items.forEach(item -> {
            item.paid();
            repo.savePayableStatus(item);
        });
        repo.updatePayment(p);
        repo.saveBasketStatus(b.paid());
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, settings.businessZone());
        String number = Receipt.number(p.region(), p.channel(), day.getYear(),
                repo.nextReceiptNumber(p.region(), p.channel(), day.getYear()));
        List<Receipt.Line> lines = new ArrayList<>();
        for (Payable item : items) {
            for (Payable.Line l : item.lines()) {
                lines.add(new Receipt.Line(item.id(), item.itemType(), item.itemRef(), item.trafficFileNumber(),
                        l.financialItem(), l.beneficiary(), l.costCentre(), l.description(), l.amountMinor()));
            }
        }
        Receipt r = new Receipt(UUID.randomUUID(), number, p.id(), Receipt.typeOf(items), p.region(), p.channel(),
                p.method(), p.currency(), p.amountMinor(), lines, p.payerId(), now, day, Receipt.Status.ISSUED, null,
                null, null);
        repo.insertReceipt(r);
        repo.insertLedgerPostings(r);
        if (Payment.COUNTER_METHODS.contains(p.method())) {
            repo.recordTillPayment(p, r);
        }
        Map<String, Object> event = new LinkedHashMap<>(paymentSnapshot(p));
        event.put("receiptNumber", number);
        event.put("receiptType", r.type().name());
        event.put("items", items.stream().map(i -> Map.of("payableId", i.id().toString(), "itemType",
                i.itemType().name(), "itemRef", i.itemRef(), "trafficFileNumber",
                i.trafficFileNumber() == null ? "" : i.trafficFileNumber())).toList());
        audit.record("PAYMENT_CONFIRMED", "Payment", p.id().toString(), null, event, by);
        events.record("itms.payment.confirmed", p.id().toString(), event);
        events.record("itms.payment.ledger-posting-ready", r.id().toString(), Map.of("receiptId", r.id().toString(),
                "receiptNumber", r.number(), "region", r.region(), "currency", r.currency(),
                "lineCount", r.lines().size()));
        return r;
    }

    private void raiseException(Payment p, String why) {
        Map<String, Object> m = new LinkedHashMap<>(paymentSnapshot(p));
        m.put("why", why);
        audit.record("PAYMENT_EXCEPTION", "Payment", p.id().toString(), null, m, why);
        events.record("itms.payment.exception", p.id().toString(), m);
        LOG.warn("Payment {} needs the finance queue: {}", p.id(), why);
    }

    // ---- pending resolution (FN-005)

    /** Asks the gateway about PENDING payments; unresolved ones past the window go to EXCEPTION. */
    @Transactional
    public int resolvePending(int limit) {
        Instant now = clock.instant();
        CurrentActor system = CurrentActor.system("payment-pending-job");
        List<Payment> pending = repo.pendingForInquiry(now.minus(settings.inquiryAfter()), limit);
        for (Payment p : pending) {
            Ports.PaymentGateway.StatusAnswer answer;
            try {
                answer = gateway.status(p.id());
            } catch (Ports.GatewayUnavailableException e) {
                answer = new Ports.PaymentGateway.StatusAnswer(Ports.PaymentGateway.RemoteStatus.UNKNOWN, null, 0,
                        null, null);
            }
            switch (answer.status()) {
                case SUCCESS -> {
                    repo.logCallback(p.id(), answer.gatewayTxnId(), "SUCCESS", "INQUIRY", now);
                    confirm(p, answer.gatewayTxnId(), answer.amountMinor(), answer.currency(), now,
                            "status-inquiry");
                }
                case FAILED -> {
                    repo.logCallback(p.id(), answer.gatewayTxnId(), "FAILED", "INQUIRY", now);
                    p.fail(answer.gatewayTxnId());
                    repo.updatePayment(p);
                    events.recordAs(system, "itms.payment.failed", p.id().toString(), paymentSnapshot(p));
                }
                default -> {
                    if (!now.isBefore(p.createdAt().plus(settings.resolutionWindow()))) {
                        p.unresolved(now);
                        repo.updatePayment(p);
                        raiseException(p, "no gateway confirmation within " + settings.resolutionWindow());
                    } else {
                        p.inquired(now);
                        repo.updatePayment(p);
                    }
                }
            }
        }
        return pending.size();
    }

    // ---- receipts (FN-003, FN-011, FN-014)

    @Transactional
    public Receipt receipt(String number, Caller caller) {
        Receipt r = repo.findReceipt(number).orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                "RECEIPT_NOT_FOUND", "receipt not found", List.of("FN-014")));
        boolean own = caller.actor().id().equals(r.payerId());
        require(own || caller.grants().holds(RECEIPT_READ));
        if (!own) {
            audit.record("RECEIPT_VIEWED", "Receipt", number, null, null, null);
        }
        return r;
    }

    @Transactional
    public List<Receipt> searchReceipts(Ports.PaymentRepository.ReceiptQuery q, Caller caller) {
        require(caller.grants().holds(RECEIPT_READ));
        if (q.limit() < 1 || q.limit() > 500) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1..500", List.of("FN-014"), "limit");
        }
        // CC-001: only receipts of regions the caller's receipt.read grants reach (a page may then hold fewer rows).
        List<Receipt> found = repo.searchReceipts(q).stream()
                .filter(r -> caller.grants().allowsInRegion(RECEIPT_READ, r.region())).toList();
        audit.record("RECEIPTS_SEARCHED", "Receipt", "search", null, Map.of("count", found.size()), null);
        return found;
    }

    @Transactional
    public Receipt cancelReceipt(String number, String reason, Caller caller) {
        channelOf(caller);
        String why = reason(reason);
        Receipt r = repo.findReceiptForUpdate(number).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "RECEIPT_NOT_FOUND", "receipt not found", List.of("FN-011")));
        require(caller.grants().allowsInRegion(RECEIPT_CANCEL, r.region()));
        r.cancel(caller.actor().id(), why, clock.instant(), settings.businessZone());
        repo.saveReceiptCancellation(r);
        List<UUID> ids = r.lines().stream().map(Receipt.Line::payableId).distinct().toList();
        for (Payable p : repo.lockPayables(ids)) {
            p.reopen();
            repo.savePayableStatus(p);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("receiptNumber", number);
        event.put("paymentId", r.paymentId().toString());
        event.put("items", r.lines().stream().map(l -> Map.of("itemType", l.itemType().name(), "itemRef",
                l.itemRef())).distinct().toList());
        audit.record("RECEIPT_CANCELLED", "Receipt", number, Map.of("status", "ISSUED"), event, why);
        events.record("itms.payment.receipt-cancelled", number, event);
        return r;
    }

    // ---- helpers

    static Map<String, Object> payableSnapshot(Payable p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payableId", p.id().toString());
        m.put("itemType", p.itemType().name());
        m.put("itemRef", p.itemRef());
        m.put("trafficFileNumber", p.trafficFileNumber());
        m.put("region", p.region());
        m.put("total", p.total());
        m.put("currency", p.currency());
        m.put("status", p.status().name());
        return m;
    }

    static Map<String, Object> paymentSnapshot(Payment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", p.id().toString());
        m.put("basketId", p.basketId().toString());
        m.put("method", p.method().name());
        m.put("amount", p.amountMinor());
        m.put("currency", p.currency());
        m.put("channel", p.channel());
        m.put("region", p.region());
        m.put("payerId", p.payerId());
        m.put("status", p.status().name());
        m.put("gatewayTxnId", p.gatewayTxnId());
        return m;
    }

    static String reason(String value) {
        String v = value == null ? "" : value.strip();
        if (v.length() < 3 || v.length() > 500 || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("FN-011"));
        }
        return v;
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with payments", List.of("CC-001"));
        }
    }

    static String channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw DomainException.validation("CHANNEL_REQUIRED", "the request does not name its channel (X-Channel)",
                    List.of("CC-120"));
        }
        return caller.channel().name();
    }

    private static DomainException notFound(String what, UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, what + "_NOT_FOUND",
                what.toLowerCase(java.util.Locale.ROOT) + " " + id + " not found", List.of("FN-002"));
    }
}

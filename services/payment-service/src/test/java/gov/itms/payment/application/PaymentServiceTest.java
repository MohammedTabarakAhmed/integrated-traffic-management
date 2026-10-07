package gov.itms.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.payment.domain.Actor;
import gov.itms.payment.domain.Basket;
import gov.itms.payment.domain.Channel;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FN-004")
class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final String GOOD_SIGNATURE = "a".repeat(64);

    private final MutableClock clock = new MutableClock(NOW);
    private final Repo repo = new Repo();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private Ports.PaymentGateway.StatusAnswer gatewayAnswer;
    private final PaymentService service = new PaymentService(repo, new Ports.PaymentGateway() {
        @Override
        public Session openSession(UUID paymentId, long amountMinor, String currency, String returnUrl,
                                   String summary) {
            return new Session("sess-" + paymentId, "https://gw.example/pay/" + paymentId);
        }

        @Override
        public StatusAnswer status(UUID paymentId) {
            if (gatewayAnswer == null) {
                throw new Ports.GatewayUnavailableException("down", null);
            }
            return gatewayAnswer;
        }
    }, (paymentId, txn, status, amount, currency, paidAt, signature) -> GOOD_SIGNATURE.equals(signature),
            new AuditRecorder() {
                @Override
                public void record(String action, String entityType, String entityId, Object oldValue,
                                   Object newValue, String reason) {
                    audit.add(action + " " + entityId);
                }

                @Override
                public void recordAs(CurrentActor actor, String action, String entityType, String entityId,
                                     Object oldValue, Object newValue, String reason) {
                    record(action, entityType, entityId, oldValue, newValue, reason);
                }
            }, new DomainEventRecorder() {
                @Override
                public void record(String eventType, String subject, Object data) {
                    events.add(eventType + " " + subject);
                }

                @Override
                public void recordAs(CurrentActor actor, String eventType, String subject, Object data) {
                    record(eventType, subject, data);
                }
            }, new PaymentService.Settings(Set.of(Payment.Method.GATEWAY_CARD, Payment.Method.CASH),
            Duration.ofMinutes(5), Duration.ofMinutes(30), ZoneOffset.UTC), clock);

    private static Caller caller(String id, Set<String> regions, String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor(id, id), Channel.PORTAL, new Caller.Grants() {
            @Override
            public boolean allowsInRegion(String permission, String region) {
                return held.contains(permission) && regions.contains(region);
            }

            @Override
            public boolean holds(String permission) {
                return held.contains(permission);
            }
        });
    }

    private static final Caller OWNER = caller("svc-violation", Set.of("NORTH"), PaymentService.PAYABLE_REGISTER);
    private static final Caller CUSTOMER = caller("c-1", Set.of());
    private static final Caller GATEWAY = caller("svc-gateway", Set.of(), PaymentService.CALLBACK);
    private static final Caller CASHIER = caller("u-cashier", Set.of("NORTH"), PaymentService.COLLECT);

    private Payable payable(String ref, long amount) {
        return service.register(Payable.ItemType.VIOLATION, ref, "TF-1", "NORTH", "XTS",
                List.of(new Payable.Line("FINE_REVENUE", "AUTHORITY", null, amount)), OWNER);
    }

    private Payment startedOnline(long amount) {
        Payable p = payable("VF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), amount);
        Basket b = service.basket(List.of(p.id()), null, CUSTOMER);
        return service.pay(b.id(), Payment.Method.GATEWAY_CARD, "https://portal/return", null, CUSTOMER).payment();
    }

    @Test
    @Tag("UAT-4")
    @DisplayName("UAT-4, FN-004: two identical SUCCESS callbacks give one receipt; the second is logged DUPLICATE")
    void duplicateCallback() {
        Payment p = startedOnline(50000);
        PaymentService.CallbackOutcome first = service.callback(p.id(), "gw-1", "SUCCESS", 50000, "XTS",
                NOW.toString(), GOOD_SIGNATURE, GATEWAY);
        PaymentService.CallbackOutcome second = service.callback(p.id(), "gw-1", "SUCCESS", 50000, "XTS",
                NOW.toString(), GOOD_SIGNATURE, GATEWAY);
        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.receipt().number()).isEqualTo(first.receipt().number());
        assertThat(repo.receipts).hasSize(1);
        assertThat(repo.callbacks).containsExactly(p.id() + " ACCEPTED", p.id() + " DUPLICATE");
        assertThat(events.stream().filter(e -> e.startsWith("itms.payment.confirmed")).count()).isEqualTo(1);
        assertThat(first.receipt().type()).isEqualTo(Receipt.Type.VIOLATION);
        assertThat(first.receipt().number()).isEqualTo("R-NORTH-PORTAL-2026-00000001");
        assertThat(repo.payables.values()).allMatch(x -> x.status() == Payable.Status.PAID);
    }

    @Test
    @DisplayName("FN-004: a bad signature is refused and audited; a mismatch goes to the finance queue")
    void signatureAndMismatch() {
        Payment p = startedOnline(50000);
        assertThatThrownBy(() -> service.callback(p.id(), "gw-1", "SUCCESS", 50000, "XTS", null, "b".repeat(64),
                GATEWAY)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("SIGNATURE_INVALID");
        assertThat(audit).contains("PAYMENT_CALLBACK_REJECTED " + p.id());
        assertThatThrownBy(() -> service.callback(p.id(), "gw-1", "SUCCESS", 50000, "XTS", null, GOOD_SIGNATURE,
                CUSTOMER)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");

        PaymentService.CallbackOutcome mismatch = service.callback(p.id(), "gw-1", "SUCCESS", 49999, "XTS", null,
                GOOD_SIGNATURE, GATEWAY);
        assertThat(mismatch.receipt()).isNull();
        assertThat(repo.payments.get(p.id()).status()).isEqualTo(Payment.Status.EXCEPTION);
        assertThat(events).contains("itms.payment.exception " + p.id());
        assertThat(repo.receipts).isEmpty();
    }

    @Test
    @DisplayName("FN-004: an item paid meanwhile at a counter sends the online payment to EXCEPTION, no second receipt")
    void itemPaidMeanwhile() {
        Payable fine = payable("VF-SHARED", 50000);
        Basket online = service.basket(List.of(fine.id()), null, CUSTOMER);
        Payment p = service.pay(online.id(), Payment.Method.GATEWAY_CARD, "https://portal/return", null, CUSTOMER)
                .payment();
        Basket counter = service.basket(List.of(fine.id()), null, CASHIER);
        PaymentService.Started cash = service.pay(counter.id(), Payment.Method.CASH, null, "TILL-1", CASHIER);
        assertThat(cash.receipt()).isNotNull();

        PaymentService.CallbackOutcome late = service.callback(p.id(), "gw-1", "SUCCESS", 50000, "XTS", null,
                GOOD_SIGNATURE, GATEWAY);
        assertThat(late.receipt()).isNull();
        assertThat(repo.payments.get(p.id()).status()).isEqualTo(Payment.Status.EXCEPTION);
        assertThat(repo.payments.get(p.id()).exceptionReason()).isEqualTo("ITEM_NOT_OPEN:VF-SHARED");
        assertThat(repo.receipts).hasSize(1);
    }

    @Test
    @Tag("FN-005")
    @DisplayName("FN-005: the pending job confirms on SUCCESS, fails on FAILED, and gives up after the window")
    void pendingJob() {
        Payment success = startedOnline(100);
        clock.advance(Duration.ofMinutes(6));
        gatewayAnswer = new Ports.PaymentGateway.StatusAnswer(Ports.PaymentGateway.RemoteStatus.SUCCESS, "gw-s", 100,
                "XTS", NOW);
        assertThat(service.resolvePending(10)).isEqualTo(1);
        assertThat(repo.payments.get(success.id()).status()).isEqualTo(Payment.Status.CONFIRMED);
        assertThat(repo.callbacks).contains(success.id() + " INQUIRY");

        Payment failed = startedOnline(100);
        clock.advance(Duration.ofMinutes(6));
        gatewayAnswer = new Ports.PaymentGateway.StatusAnswer(Ports.PaymentGateway.RemoteStatus.FAILED, "gw-f", 0,
                null, null);
        service.resolvePending(10);
        assertThat(repo.payments.get(failed.id()).status()).isEqualTo(Payment.Status.FAILED);

        Payment silent = startedOnline(100);
        gatewayAnswer = null;
        clock.advance(Duration.ofMinutes(6));
        service.resolvePending(10);
        assertThat(repo.payments.get(silent.id()).status()).as("inside the window").isEqualTo(Payment.Status.PENDING);
        assertThat(repo.payments.get(silent.id()).lastInquiryAt()).isNotNull();
        clock.advance(Duration.ofMinutes(25));
        service.resolvePending(10);
        assertThat(repo.payments.get(silent.id()).status()).isEqualTo(Payment.Status.EXCEPTION);
        assertThat(events).contains("itms.payment.exception " + silent.id());
    }

    @Test
    @Tag("FN-001")
    @Tag("FN-011")
    @DisplayName("FN-001, FN-011: methods and scope are checked; a same-day cancellation reopens the items")
    void methodsScopeAndCancellation() {
        Payable fine = payable("VF-C1", 700);
        Basket b = service.basket(List.of(fine.id()), null, CUSTOMER);
        assertThatThrownBy(() -> service.pay(b.id(), Payment.Method.CHEQUE, null, "CHQ-1", CASHIER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("METHOD_NOT_ACCEPTED");
        assertThatThrownBy(() -> service.pay(b.id(), Payment.Method.CASH, null, "TILL-1", CUSTOMER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.pay(b.id(), Payment.Method.GATEWAY_CARD, null, null, caller("c-2",
                Set.of()))).isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.pay(b.id(), Payment.Method.CASH, null, "till slip!", CASHIER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        Receipt r = service.pay(b.id(), Payment.Method.CASH, null, "TILL-1", CASHIER).receipt();
        assertThatThrownBy(() -> service.pay(b.id(), Payment.Method.CASH, null, "TILL-2", CASHIER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("BASKET_PAID");

        Caller supervisor = caller("u-super", Set.of("NORTH"), PaymentService.RECEIPT_CANCEL);
        service.cancelReceipt(r.number(), "wrong customer", supervisor);
        assertThat(repo.payables.get(fine.id()).status()).isEqualTo(Payable.Status.OPEN);
        assertThat(events).contains("itms.payment.receipt-cancelled " + r.number());
        assertThatThrownBy(() -> service.register(Payable.ItemType.VIOLATION, "VF-C1", "TF-1", "SOUTH", "XTS",
                List.of(new Payable.Line("FINE_REVENUE", "AUTHORITY", null, 1)), OWNER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> payable("VF-C1", 700)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("PAYABLE_EXISTS");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class Repo implements Ports.PaymentRepository {
        final Map<UUID, Payable> payables = new LinkedHashMap<>();
        final Map<UUID, Basket> baskets = new HashMap<>();
        final Map<UUID, Payment> payments = new LinkedHashMap<>();
        final Map<String, Receipt> receipts = new LinkedHashMap<>();
        final List<String> callbacks = new ArrayList<>();
        final Map<String, Long> sequences = new HashMap<>();

        @Override
        public void insertPayable(Payable payable) {
            payables.put(payable.id(), payable);
        }

        @Override
        public Optional<Payable> findPayable(UUID id) {
            return Optional.ofNullable(payables.get(id));
        }

        @Override
        public List<Payable> lockPayables(List<UUID> ids) {
            return ids.stream().sorted().map(payables::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public Optional<Payable> findPayableByItem(Payable.ItemType type, String itemRef) {
            return payables.values().stream().filter(p -> p.itemType() == type && p.itemRef().equals(itemRef))
                    .max(Comparator.comparing(Payable::createdAt));
        }

        @Override
        public void savePayableStatus(Payable payable) {
        }

        @Override
        public void insertBasket(Basket basket) {
            baskets.put(basket.id(), basket);
        }

        @Override
        public Optional<Basket> findBasket(UUID id) {
            return Optional.ofNullable(baskets.get(id));
        }

        @Override
        public void saveBasketStatus(Basket basket) {
            baskets.put(basket.id(), basket);
        }

        @Override
        public void insertPayment(Payment payment) {
            payments.put(payment.id(), payment);
        }

        @Override
        public Optional<Payment> findPayment(UUID id) {
            return Optional.ofNullable(payments.get(id));
        }

        @Override
        public Optional<Payment> findPaymentForUpdate(UUID id) {
            return findPayment(id);
        }

        @Override
        public void updatePayment(Payment payment) {
            payments.put(payment.id(), payment);
        }

        @Override
        public boolean pendingPaymentExists(UUID basketId) {
            return payments.values().stream().anyMatch(p -> p.basketId().equals(basketId)
                    && p.status() == Payment.Status.PENDING);
        }

        @Override
        public List<Payment> pendingForInquiry(Instant olderThan, int limit) {
            return payments.values().stream().filter(p -> p.status() == Payment.Status.PENDING
                    && Payment.GATEWAY_METHODS.contains(p.method()) && p.createdAt().isBefore(olderThan))
                    .limit(limit).toList();
        }

        @Override
        public void logCallback(UUID paymentId, String gatewayTxnId, String status, String outcome, Instant at) {
            callbacks.add(paymentId + " " + outcome);
        }

        @Override
        public long nextReceiptNumber(String region, String channel, int year) {
            return sequences.merge(region + "/" + channel + "/" + year, 1L, Long::sum);
        }

        @Override
        public void insertReceipt(Receipt receipt) {
            receipts.put(receipt.number(), receipt);
        }

        @Override
        public Optional<Receipt> findReceipt(String number) {
            return Optional.ofNullable(receipts.get(number));
        }

        @Override
        public Optional<Receipt> findReceiptForUpdate(String number) {
            return findReceipt(number);
        }

        @Override
        public Optional<Receipt> findReceiptByPayment(UUID paymentId) {
            return receipts.values().stream().filter(r -> r.paymentId().equals(paymentId)).findFirst();
        }

        @Override
        public void saveReceiptCancellation(Receipt receipt) {
        }

        @Override
        public List<Receipt> searchReceipts(ReceiptQuery query) {
            return List.copyOf(receipts.values());
        }
    }
}

package gov.itms.payment.application;

import gov.itms.payment.application.FinanceRepository.DepositReceipt;
import gov.itms.payment.application.FinanceRepository.PaymentContext;
import gov.itms.payment.application.Ports.PaymentGateway;
import gov.itms.payment.domain.DomainException;
import gov.itms.payment.domain.Payment;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Finance operations beyond payment capture: refunds, tills, bank deposits, settlement matching (Increment 5). */
public class FinanceOperations {

    public static final String REFUND_REQUEST = "payment.refund.request";
    public static final String REFUND_APPROVE = "payment.refund.approve";
    public static final String SETTLEMENT_IMPORT = "payment.settlement.import";
    public static final String FINANCE_READ = "payment.finance.read";
    public static final String FINANCE_RESOLVE = "payment.finance.resolve";
    public static final String TILL_MANAGE = "payment.till.manage";

    private static final String SOURCE = "^[A-Z][A-Z0-9_]{1,39}$";
    private static final String CURRENCY = "^[A-Z]{3}$";

    public record Refund(UUID id, UUID paymentId, String receiptNumber, String region, String currency,
                         long amountMinor, String reason, String status, String requestedBy, Instant requestedAt,
                         String decidedBy, Instant decidedAt, String remoteRef) { }

    public record SettlementLine(String source, String externalRef, Instant paidAt, String region, String currency,
                                 long amountMinor) { }

    public record Reconciliation(UUID id, String source, String externalRef, Instant paidAt, String region,
                                 String currency, long amountMinor, UUID paymentId, String status, String reason,
                                 Instant receivedAt) { }

    public record Till(UUID id, String cashierId, String region, long openingAmountMinor, Instant openedAt,
                       String status, Instant closedAt) { }

    public record TillTotal(Payment.Method method, long expectedMinor, long actualMinor, long differenceMinor) { }

    public record BankDeposit(UUID id, String depositRef, String bank, String region, String currency, long totalMinor,
                              String status, String createdBy, Instant createdAt, Instant matchedAt, String reason) { }

    public record BankStatementLine(String source, String externalRef, String bank, Instant bookedAt, String region,
                                    String currency, long amountMinor) { }

    public record BankStatementResult(UUID id, String source, String externalRef, String bank, String region,
                                      String currency, long amountMinor, UUID depositId, String status, String reason,
                                      Instant receivedAt) { }

    public record ClosedTill(Till till, List<TillTotal> totals) {
        public ClosedTill {
            totals = totals == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(totals));
        }
    }

    private final FinanceRepository repo;
    private final CurrentActorProvider actors;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;
    private final PaymentGateway gateway;

    public FinanceOperations(FinanceRepository repo, CurrentActorProvider actors, AuditRecorder audit,
                             DomainEventRecorder events, Clock clock, PaymentGateway gateway) {
        this.repo = repo;
        this.actors = actors;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.gateway = gateway;
    }

    // ---- tills (FN-020)

    @Transactional
    public Till openTill(String region, long openingAmountMinor, Caller caller) {
        requireTillScope(caller, region);
        if (openingAmountMinor < 0) {
            throw DomainException.validation("TILL_OPENING_INVALID", "opening amount cannot be negative",
                    List.of("FN-020"));
        }
        Till till = new Till(UUID.randomUUID(), caller.actor().id(), region, openingAmountMinor, clock.instant(),
                "OPEN", null);
        if (!repo.insertTill(till)) {
            throw DomainException.conflict("TILL_ALREADY_OPEN", "cashier already has an open till in this region",
                    List.of("FN-020"));
        }
        audit.record("TILL_OPENED", "Till", till.id().toString(), null,
                Map.of("region", region, "openingAmountMinor", openingAmountMinor), null);
        return till;
    }

    @Transactional
    public ClosedTill closeTill(UUID id, List<TillTotal> declaredTotals, String reason, Caller caller) {
        Till till = repo.lockTill(id).orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                "TILL_NOT_FOUND", "till " + id + " does not exist", List.of("FN-020")));
        requireTillScope(caller, till.region());
        if (!till.cashierId().equals(caller.actor().id())) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "TILL_OWNER_REQUIRED",
                    "only the cashier who opened the till may close it", List.of("FN-020"));
        }
        if (!"OPEN".equals(till.status())) {
            throw DomainException.conflict("TILL_ALREADY_CLOSED", "the till is already closed", List.of("FN-020"));
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw DomainException.validation("TILL_CLOSE_REASON_REQUIRED", "give a closing note",
                    List.of("FN-020"));
        }
        EnumMap<Payment.Method, Long> expected = new EnumMap<>(Payment.Method.class);
        for (Payment.Method method : Payment.COUNTER_METHODS) {
            expected.put(method, 0L);
        }
        expected.putAll(repo.tillTakings(id));
        expected.compute(Payment.Method.CASH, (method, amount) -> Math.addExact(amount, till.openingAmountMinor()));
        EnumMap<Payment.Method, Long> actual = new EnumMap<>(Payment.Method.class);
        if (declaredTotals != null) {
            for (TillTotal total : declaredTotals) {
                if (!Payment.COUNTER_METHODS.contains(total.method()) || total.actualMinor() < 0
                        || actual.putIfAbsent(total.method(), total.actualMinor()) != null) {
                    throw DomainException.validation("TILL_TOTALS_INVALID", "supply one non-negative total per method",
                            List.of("FN-020"));
                }
            }
        }
        List<TillTotal> totals = Payment.COUNTER_METHODS.stream().sorted().map(method -> {
            long expectedMinor = expected.get(method);
            long actualMinor = actual.getOrDefault(method, 0L);
            TillTotal total = new TillTotal(method, expectedMinor, actualMinor,
                    Math.subtractExact(actualMinor, expectedMinor));
            repo.insertTillTotal(id, total);
            return total;
        }).toList();
        Instant now = clock.instant();
        repo.closeTill(id, caller.actor().id(), now, reason.strip());
        audit.record("TILL_CLOSED", "Till", id.toString(), Map.of("status", "OPEN"),
                Map.of("status", "CLOSED", "differences", totals.stream().map(TillTotal::differenceMinor).toList()),
                reason);
        return new ClosedTill(new Till(till.id(), till.cashierId(), till.region(), till.openingAmountMinor(),
                till.openedAt(), "CLOSED", now), totals);
    }

    // ---- bank deposits (FN-021)

    @Transactional
    public BankDeposit recordBankDeposit(String depositRef, String bank, List<String> receiptNumbers, Caller caller) {
        if (!caller.grants().holds(SETTLEMENT_IMPORT)) {
            throw forbidden(SETTLEMENT_IMPORT);
        }
        if (depositRef == null || depositRef.isBlank() || depositRef.length() > 120 || bank == null
                || bank.isBlank() || bank.length() > 120 || receiptNumbers == null || receiptNumbers.isEmpty()
                || receiptNumbers.size() > 500 || receiptNumbers.stream().distinct().count() != receiptNumbers.size()) {
            throw DomainException.validation("BANK_DEPOSIT_INVALID",
                    "deposit reference, bank and unique receipts are required", List.of("FN-021"));
        }
        List<DepositReceipt> receipts = repo.lockReceipts(receiptNumbers);
        if (receipts.size() != receiptNumbers.size() || receipts.stream().anyMatch(r -> !"ISSUED".equals(r.status()))) {
            throw DomainException.validation("BANK_DEPOSIT_RECEIPTS_INVALID",
                    "all linked receipts must exist and remain issued", List.of("FN-021"));
        }
        String region = receipts.getFirst().region();
        String currency = receipts.getFirst().currency();
        if (receipts.stream().anyMatch(r -> !region.equals(r.region()) || !currency.equals(r.currency()))) {
            throw DomainException.validation("BANK_DEPOSIT_SCOPE_MISMATCH",
                    "a deposit group must use one region and currency", List.of("FN-021"));
        }
        requireFinanceRegion(caller, SETTLEMENT_IMPORT, region);
        long total = receipts.stream().mapToLong(DepositReceipt::amountMinor).reduce(0, Math::addExact);
        BankDeposit deposit = new BankDeposit(UUID.randomUUID(), depositRef.strip(), bank.strip(), region, currency,
                total, "OPEN", caller.actor().id(), clock.instant(), null, null);
        if (!repo.insertDeposit(deposit, receipts)) {
            throw DomainException.conflict("BANK_DEPOSIT_REFERENCE_EXISTS", "deposit reference is already recorded",
                    List.of("FN-021"));
        }
        audit.record("BANK_DEPOSIT_RECORDED", "BankDeposit", deposit.id().toString(), null,
                Map.of("depositRef", deposit.depositRef(), "region", region, "currency", currency,
                        "totalMinor", total, "receiptCount", receipts.size()), null);
        return repo.deposit(deposit.id());
    }

    @Transactional
    public List<BankStatementResult> importBankStatement(List<BankStatementLine> lines, Caller caller) {
        if (!caller.grants().holds(SETTLEMENT_IMPORT)) {
            throw forbidden(SETTLEMENT_IMPORT);
        }
        if (lines == null || lines.isEmpty() || lines.size() > 10000) {
            throw DomainException.validation("BANK_STATEMENT_INVALID", "statement must contain 1..10000 rows",
                    List.of("FN-021", "FN-040"));
        }
        for (BankStatementLine line : lines) {
            if (line.source() == null || !line.source().matches(SOURCE)
                    || line.externalRef() == null || line.externalRef().isBlank() || line.externalRef().length() > 200
                    || line.bank() == null || line.bank().isBlank() || line.bank().length() > 120
                    || line.region() == null || line.currency() == null || !line.currency().matches(CURRENCY)
                    || line.amountMinor() < 0) {
                throw DomainException.validation("BANK_STATEMENT_LINE_INVALID", "statement row is malformed",
                        List.of("FN-021", "FN-040"));
            }
            requireFinanceRegion(caller, SETTLEMENT_IMPORT, line.region());
            BankDeposit deposit = repo.lockMatchingDeposit(line).orElse(null);
            Instant now = clock.instant();
            repo.insertStatementLine(line, deposit == null ? null : deposit.id(),
                    deposit == null ? "EXCEPTION" : "MATCHED",
                    deposit == null ? "No open deposit matches bank reference, amount and scope" : null, now);
            if (deposit != null) {
                repo.markDepositMatched(deposit.id(), caller.actor().id(), now);
                audit.record("BANK_DEPOSIT_MATCHED", "BankDeposit", deposit.id().toString(),
                        Map.of("status", "OPEN"), Map.of("status", "MATCHED", "statementRef", line.externalRef()), null);
            }
        }
        return repo.latestStatementLines(lines.getFirst().source(), lines.size());
    }

    @Transactional(readOnly = true)
    public List<BankStatementResult> bankDepositExceptions(int limit, Caller caller) {
        if (!caller.grants().holds(FINANCE_READ) || limit < 1 || limit > 1000) {
            throw forbidden(FINANCE_READ);
        }
        return repo.statementExceptions(limit);
    }

    @Transactional
    public BankStatementResult resolveBankDepositException(UUID id, UUID depositId, String reason, Caller caller) {
        if (!caller.grants().holds(FINANCE_RESOLVE)) {
            throw forbidden(FINANCE_RESOLVE);
        }
        BankStatementResult line = repo.lockStatementLine(id).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "BANK_STATEMENT_NOT_FOUND", "statement line " + id + " not found",
                List.of("FN-021", "FN-041")));
        if (!"EXCEPTION".equals(line.status())) {
            throw DomainException.conflict("BANK_STATEMENT_RESOLVED", "statement line is already reconciled",
                    List.of("FN-041"));
        }
        String why = reason(reason);
        BankDeposit deposit = repo.lockOpenDeposit(depositId).orElseThrow(() -> DomainException.validation(
                "BANK_DEPOSIT_MISMATCH", "deposit must be open for reconciliation", List.of("FN-041")));
        requireFinanceRegion(caller, FINANCE_RESOLVE, deposit.region());
        if (!line.externalRef().equals(deposit.depositRef()) || !line.bank().equals(deposit.bank())
                || !line.region().equals(deposit.region()) || !line.currency().equals(deposit.currency())
                || line.amountMinor() != deposit.totalMinor()) {
            throw DomainException.validation("BANK_DEPOSIT_MISMATCH", "statement line and deposit fields differ",
                    List.of("FN-041"));
        }
        if (repo.issuedReceiptTotal(depositId) != deposit.totalMinor()) {
            throw DomainException.validation("BANK_DEPOSIT_RECEIPTS_CHANGED",
                    "deposit receipts must remain issued and retain their recorded total", List.of("FN-021", "FN-041"));
        }
        Instant now = clock.instant();
        repo.resolveStatementLine(id, depositId, why, caller.actor().id(), now);
        repo.markDepositMatched(depositId, caller.actor().id(), now);
        audit.record("BANK_DEPOSIT_EXCEPTION_RESOLVED", "BankDeposit", depositId.toString(),
                Map.of("status", "OPEN"), Map.of("status", "MATCHED", "statementLineId", id.toString()), why);
        return repo.statementLine(id);
    }

    // ---- refunds (FN-012)

    @Transactional
    public Refund requestRefund(UUID paymentId, long amountMinor, String reason, Caller caller) {
        if (!caller.grants().holds(REFUND_REQUEST)) {
            throw forbidden(REFUND_REQUEST);
        }
        if (amountMinor < 1 || reason == null || reason.isBlank() || reason.length() > 500) {
            throw DomainException.validation("REFUND_INVALID", "refund amount and reason are required",
                    List.of("FN-012"));
        }
        PaymentContext p = repo.payment(paymentId, true).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "PAYMENT_NOT_FOUND", "payment " + paymentId + " has no receipt",
                List.of("FN-012")));
        // CC-001: the request is made in the payment's region, like its approval (the approval limit is per region).
        requireFinanceRegion(caller, REFUND_REQUEST, p.region());
        if (!"CONFIRMED".equals(p.status())) {
            throw DomainException.conflict("PAYMENT_NOT_CONFIRMED", "only a confirmed payment can be refunded",
                    List.of("FN-012"));
        }
        if (amountMinor > p.amountMinor() - repo.openRefundTotal(paymentId)) {
            throw DomainException.validation("REFUND_EXCEEDS_PAID", "refunds exceed the remaining paid amount",
                    List.of("FN-012"), p.currency());
        }
        Refund refund = new Refund(UUID.randomUUID(), paymentId, p.receiptNumber(), p.region(), p.currency(),
                amountMinor, reason.strip(), "PENDING", caller.actor().id(), clock.instant(), null, null, null);
        repo.insertRefund(refund);
        audit.record("REFUND_REQUESTED", "Payment", paymentId.toString(), null,
                Map.of("refundId", refund.id().toString(), "amountMinor", amountMinor, "currency", p.currency()),
                reason);
        return repo.refund(refund.id());
    }

    @Transactional
    public Refund decideRefund(UUID id, boolean approve, String reason, Caller caller) {
        if (!caller.grants().holds(REFUND_APPROVE)) {
            throw forbidden(REFUND_APPROVE);
        }
        Refund current = repo.lockRefund(id).orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                "REFUND_NOT_FOUND", "refund " + id + " not found", List.of("FN-012")));
        if (!"PENDING".equals(current.status())) {
            throw DomainException.conflict("REFUND_DECIDED", "refund is already " + current.status(),
                    List.of("FN-012"));
        }
        if (current.requestedBy().equals(caller.actor().id())) {
            throw new DomainException(DomainException.Kind.SEGREGATION_OF_DUTIES, "SAME_MAKER_CHECKER",
                    "the refund maker cannot decide the refund", List.of("FN-012", "CC-002"));
        }
        long limit = actors.authorisation().approvalLimit("REFUND_AMOUNT", current.currency(),
                new ScopeTarget(current.region(), null, null)).orElse(-1);
        if (approve && (limit < 0 || current.amountMinor() > limit)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "APPROVAL_LIMIT_EXCEEDED",
                    "the refund exceeds the caller's approval limit", List.of("FN-012", "CC-003"), limit);
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw DomainException.validation("REASON_INVALID", "a reason is required", List.of("FN-012"));
        }
        String status = approve ? "APPROVED" : "REJECTED";
        String remoteRef = null;
        if (approve) {
            PaymentContext payment = repo.payment(current.paymentId(), false).orElseThrow();
            if (onlineMethod(payment.method())) {
                remoteRef = gateway.refund(current.paymentId(), payment.gatewayTxnId(), id.toString(),
                        current.amountMinor(), current.currency());
                status = "SUBMITTED";
            }
        }
        repo.decideRefund(id, status, caller.actor().id(), clock.instant(), remoteRef);
        if (approve) {
            events.record("itms.payment.refund-approved", id.toString(), Map.of("refundId", id.toString(),
                    "paymentId", current.paymentId().toString(), "receiptNumber", current.receiptNumber(),
                    "amountMinor", current.amountMinor(), "currency", current.currency(), "status", status,
                    "remoteRef", remoteRef == null ? "" : remoteRef));
        }
        audit.record("REFUND_" + status, "Refund", id.toString(), Map.of("status", "PENDING"),
                Map.of("status", status), reason);
        return repo.refund(id);
    }

    @Transactional(readOnly = true)
    public Refund refund(UUID id) {
        return repo.refund(id);
    }

    // ---- settlement reconciliation (FN-006, FN-040, FN-041)

    @Transactional
    public List<Reconciliation> importSettlements(List<SettlementLine> lines, Caller caller) {
        if (!caller.grants().holds(SETTLEMENT_IMPORT)) {
            throw forbidden(SETTLEMENT_IMPORT);
        }
        if (lines == null || lines.isEmpty() || lines.size() > 10000) {
            throw DomainException.validation("SETTLEMENT_BATCH_INVALID", "settlement batch must contain 1..10000 rows",
                    List.of("FN-006", "FN-040"));
        }
        for (SettlementLine line : lines) {
            if (line.source() == null || !line.source().matches(SOURCE)
                    || line.externalRef() == null || line.externalRef().isBlank() || line.externalRef().length() > 200
                    || line.region() == null || line.currency() == null || !line.currency().matches(CURRENCY)
                    || line.amountMinor() < 0) {
                throw DomainException.validation("SETTLEMENT_LINE_INVALID", "settlement row is malformed",
                        List.of("FN-006", "FN-040"));
            }
            UUID match = repo.matchingPayment(line).orElse(null);
            repo.insertReconciliation(line, match, match == null ? "EXCEPTION" : "MATCHED",
                    match == null ? "No matching confirmed payment" : null, clock.instant());
        }
        return repo.latestReconciliations(lines.getFirst().source(), lines.size());
    }

    @Transactional(readOnly = true)
    public List<Reconciliation> exceptions(int limit, Caller caller) {
        if (!caller.grants().holds(FINANCE_READ) || limit < 1 || limit > 1000) {
            throw forbidden(FINANCE_READ);
        }
        return repo.reconciliationExceptions(limit);
    }

    @Transactional
    public Reconciliation resolve(UUID id, UUID paymentId, String reason, Caller caller) {
        if (!caller.grants().holds(FINANCE_RESOLVE)) {
            throw forbidden(FINANCE_RESOLVE);
        }
        Reconciliation item = repo.lockReconciliation(id).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "RECONCILIATION_NOT_FOUND", "reconciliation item " + id
                + " not found", List.of("FN-040", "FN-041")));
        if (!"EXCEPTION".equals(item.status())) {
            throw DomainException.conflict("RECONCILIATION_RESOLVED", "item is already " + item.status(),
                    List.of("FN-041"));
        }
        PaymentContext payment = repo.payment(paymentId, false).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "PAYMENT_NOT_FOUND", "payment " + paymentId + " has no receipt",
                List.of("FN-041")));
        if (!"CONFIRMED".equals(payment.status()) || !item.externalRef().equals(payment.gatewayTxnId())
                || payment.amountMinor() != item.amountMinor()
                || !payment.currency().equals(item.currency()) || !payment.region().equals(item.region())) {
            throw DomainException.validation("RECONCILIATION_MISMATCH", "payment amount, currency or region differs",
                    List.of("FN-040", "FN-041"));
        }
        String why = reason(reason);
        repo.resolveReconciliation(id, paymentId, why, caller.actor().id(), clock.instant());
        audit.record("RECONCILIATION_EXCEPTION_RESOLVED", "ReconciliationItem", id.toString(),
                Map.of("status", "EXCEPTION"), Map.of("status", "RESOLVED", "paymentId", paymentId.toString()), why);
        return repo.reconciliation(id);
    }

    // ---- helpers

    private static String reason(String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3 || why.length() > 500) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("FN-041"));
        }
        return why;
    }

    private static void requireFinanceRegion(Caller caller, String permission, String region) {
        if (!caller.grants().allowsInRegion(permission, region)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "FINANCE_REGION_REQUIRED",
                    "finance permission is required in region " + region, List.of("FN-021", "FN-041"));
        }
    }

    private static void requireTillScope(Caller caller, String region) {
        if (!caller.grants().allowsInRegion(TILL_MANAGE, region)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "TILL_FORBIDDEN",
                    "till permission is required in region " + region, List.of("FN-020"));
        }
    }

    private static boolean onlineMethod(String method) {
        return "GATEWAY_CARD".equals(method) || "E_PAYMENT".equals(method) || "MOBILE_WALLET".equals(method);
    }

    private static DomainException forbidden(String permission) {
        return new DomainException(DomainException.Kind.OUT_OF_SCOPE, "FINANCE_FORBIDDEN",
                "finance permission is required: " + permission, List.of("FN-012", "FN-040", "FN-041"));
    }
}

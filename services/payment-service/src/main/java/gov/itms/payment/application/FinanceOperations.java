package gov.itms.payment.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import gov.itms.payment.domain.DomainException;
import gov.itms.payment.domain.Payment;
import gov.itms.payment.application.Ports.PaymentGateway;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Finance operations beyond payment capture: refunds, ledger postings, settlement matching (Increment 5). */
public class FinanceOperations {

    public static final String REFUND_REQUEST = "payment.refund.request";
    public static final String REFUND_APPROVE = "payment.refund.approve";
    public static final String SETTLEMENT_IMPORT = "payment.settlement.import";
    public static final String FINANCE_READ = "payment.finance.read";
    public static final String FINANCE_RESOLVE = "payment.finance.resolve";
    public static final String TILL_MANAGE = "payment.till.manage";

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

    public record ClosedTill(Till till, List<TillTotal> totals) { }

    private final JdbcClient jdbc;
    private final CurrentActorProvider actors;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;
    private final PaymentGateway gateway;

    public FinanceOperations(JdbcClient jdbc, CurrentActorProvider actors, AuditRecorder audit,
                             DomainEventRecorder events, Clock clock, PaymentGateway gateway) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.gateway = gateway;
    }

    @Transactional
    public Till openTill(String region, long openingAmountMinor, Caller caller) {
        requireTillScope(caller, region);
        if (openingAmountMinor < 0) {
            throw DomainException.validation("TILL_OPENING_INVALID", "opening amount cannot be negative",
                    List.of("FN-020"));
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            jdbc.sql("insert into cashier_till (id,cashier_id,region,opening_amount_minor,opened_at,status) "
                            + "values (:id,:cashier,:region,:opening,:at,'OPEN')")
                    .param("id", id).param("cashier", caller.actor().id()).param("region", region)
                    .param("opening", openingAmountMinor).param("at", Timestamp.from(now)).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw DomainException.conflict("TILL_ALREADY_OPEN", "cashier already has an open till in this region",
                    List.of("FN-020"));
        }
        audit.record("TILL_OPENED", "Till", id.toString(), null,
                Map.of("region", region, "openingAmountMinor", openingAmountMinor), null);
        return new Till(id, caller.actor().id(), region, openingAmountMinor, now, "OPEN", null);
    }

    @Transactional
    public ClosedTill closeTill(UUID id, List<TillTotal> declaredTotals, String reason, Caller caller) {
        Till till = jdbc.sql("select id,cashier_id,region,opening_amount_minor,opened_at,status,closed_at "
                        + "from cashier_till where id=:id for update")
                .param("id", id).query(FinanceOperations::mapTill).optional().orElseThrow(() ->
                        new DomainException(DomainException.Kind.NOT_FOUND, "TILL_NOT_FOUND", "till " + id
                                + " does not exist", List.of("FN-020")));
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
        java.util.EnumMap<Payment.Method, Long> expected = new java.util.EnumMap<>(Payment.Method.class);
        for (Payment.Method method : Payment.COUNTER_METHODS) {
            expected.put(method, 0L);
        }
        jdbc.sql("""
                select t.method, sum(t.amount_minor) as amount
                from till_payment t join receipt r on r.id=t.receipt_id
                where t.till_id=:id and r.status='ISSUED' group by t.method
                """).param("id", id).query((rs, row) -> {
                    expected.put(Payment.Method.valueOf(rs.getString("method")), rs.getLong("amount"));
                    return 1;
                }).list();
        expected.compute(Payment.Method.CASH, (method, amount) -> Math.addExact(amount,
                till.openingAmountMinor()));
        java.util.EnumMap<Payment.Method, Long> actual = new java.util.EnumMap<>(Payment.Method.class);
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
            long difference = Math.subtractExact(actualMinor, expectedMinor);
            jdbc.sql("insert into till_reconciliation (till_id,method,expected_minor,actual_minor,difference_minor) "
                            + "values (:till,:method,:expected,:actual,:difference)")
                    .param("till", id).param("method", method.name()).param("expected", expectedMinor)
                    .param("actual", actualMinor).param("difference", difference).update();
            return new TillTotal(method, expectedMinor, actualMinor, difference);
        }).toList();
        Instant now = clock.instant();
        jdbc.sql("update cashier_till set status='CLOSED',closed_by=:by,closed_at=:at,closing_reason=:reason "
                        + "where id=:id")
                .param("by", caller.actor().id()).param("at", Timestamp.from(now)).param("reason", reason.strip())
                .param("id", id).update();
        audit.record("TILL_CLOSED", "Till", id.toString(), Map.of("status", "OPEN"),
                Map.of("status", "CLOSED", "differences", totals.stream().map(TillTotal::differenceMinor).toList()),
                reason);
        return new ClosedTill(new Till(till.id(), till.cashierId(), till.region(), till.openingAmountMinor(),
                till.openedAt(), "CLOSED", now), totals);
    }

    @Transactional
    public BankDeposit recordBankDeposit(String depositRef, String bank, List<String> receiptNumbers, Caller caller) {
        if (!caller.grants().holds(SETTLEMENT_IMPORT)) {
            throw forbidden(SETTLEMENT_IMPORT);
        }
        if (depositRef == null || depositRef.isBlank() || depositRef.length() > 120 || bank == null
                || bank.isBlank() || bank.length() > 120 || receiptNumbers == null || receiptNumbers.isEmpty()
                || receiptNumbers.size() > 500 || receiptNumbers.stream().distinct().count() != receiptNumbers.size()) {
            throw DomainException.validation("BANK_DEPOSIT_INVALID", "deposit reference, bank and unique receipts are required",
                    List.of("FN-021"));
        }
        List<DepositReceipt> receipts = jdbc.sql("select id,number,region,currency,total_minor,status from receipt "
                        + "where number in (:numbers) order by number for update")
                .param("numbers", receiptNumbers).query((rs, row) -> new DepositReceipt(rs.getObject("id", UUID.class),
                        rs.getString("number"), rs.getString("region"), rs.getString("currency"),
                        rs.getLong("total_minor"), rs.getString("status"))).list();
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
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            jdbc.sql("insert into bank_deposit (id,deposit_ref,bank,region,currency,total_minor,status,created_by,created_at) "
                            + "values (:id,:ref,:bank,:region,:currency,:total,'OPEN',:by,:at)")
                    .param("id", id).param("ref", depositRef.strip()).param("bank", bank.strip()).param("region", region)
                    .param("currency", currency).param("total", total).param("by", caller.actor().id())
                    .param("at", Timestamp.from(now)).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw DomainException.conflict("BANK_DEPOSIT_REFERENCE_EXISTS", "deposit reference is already recorded",
                    List.of("FN-021"));
        }
        for (DepositReceipt receipt : receipts) {
            jdbc.sql("insert into bank_deposit_receipt (deposit_id,receipt_id,amount_minor) "
                            + "values (:deposit,:receipt,:amount)")
                    .param("deposit", id).param("receipt", receipt.id()).param("amount", receipt.amountMinor()).update();
        }
        audit.record("BANK_DEPOSIT_RECORDED", "BankDeposit", id.toString(), null,
                Map.of("depositRef", depositRef.strip(), "region", region, "currency", currency,
                        "totalMinor", total, "receiptCount", receipts.size()), null);
        return jdbc.sql("select * from bank_deposit where id=:id").param("id", id)
                .query(FinanceOperations::mapBankDeposit).single();
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
            if (line.source() == null || !line.source().matches("^[A-Z][A-Z0-9_]{1,39}$")
                    || line.externalRef() == null || line.externalRef().isBlank() || line.externalRef().length() > 200
                    || line.bank() == null || line.bank().isBlank() || line.bank().length() > 120
                    || line.region() == null || line.currency() == null || !line.currency().matches("^[A-Z]{3}$")
                    || line.amountMinor() < 0) {
                throw DomainException.validation("BANK_STATEMENT_LINE_INVALID", "statement row is malformed",
                        List.of("FN-021", "FN-040"));
            }
            requireFinanceRegion(caller, SETTLEMENT_IMPORT, line.region());
            BankDeposit deposit = jdbc.sql("select * from bank_deposit where deposit_ref=:ref and bank=:bank "
                            + "and region=:region and currency=:currency and total_minor=:amount and status='OPEN' "
                            + "and (select count(*) from bank_deposit_receipt dr join receipt r on r.id=dr.receipt_id "
                            + "where dr.deposit_id=bank_deposit.id and r.status='ISSUED') = "
                            + "(select count(*) from bank_deposit_receipt dr where dr.deposit_id=bank_deposit.id) for update")
                    .param("ref", line.externalRef()).param("bank", line.bank()).param("region", line.region())
                    .param("currency", line.currency()).param("amount", line.amountMinor())
                    .query(FinanceOperations::mapBankDeposit).optional().orElse(null);
            String status = deposit == null ? "EXCEPTION" : "MATCHED";
            UUID depositId = deposit == null ? null : deposit.id();
            String reason = deposit == null ? "No open deposit matches bank reference, amount and scope" : null;
            jdbc.sql("insert into bank_statement_line (id,source,external_ref,bank,region,currency,amount_minor,"
                            + "booked_at,deposit_id,status,reason,received_at) values (:id,:source,:ref,:bank,:region,"
                            + ":currency,:amount,:booked,:deposit,:status,:reason,:received) "
                            + "on conflict (source,external_ref) do nothing")
                    .param("id", UUID.randomUUID()).param("source", line.source()).param("ref", line.externalRef())
                    .param("bank", line.bank()).param("region", line.region()).param("currency", line.currency())
                    .param("amount", line.amountMinor()).param("booked", line.bookedAt()).param("deposit", depositId)
                    .param("status", status).param("reason", reason).param("received", Timestamp.from(clock.instant())).update();
            if (deposit != null) {
                Instant now = clock.instant();
                jdbc.sql("update bank_deposit set status='MATCHED',matched_by=:by,matched_at=:at where id=:id")
                        .param("by", caller.actor().id()).param("at", Timestamp.from(now)).param("id", deposit.id()).update();
                audit.record("BANK_DEPOSIT_MATCHED", "BankDeposit", deposit.id().toString(),
                        Map.of("status", "OPEN"), Map.of("status", "MATCHED", "statementRef", line.externalRef()), null);
            }
        }
        return jdbc.sql("select * from bank_statement_line where source=:source order by received_at desc limit :n")
                .param("source", lines.getFirst().source()).param("n", lines.size())
                .query(FinanceOperations::mapBankStatement).list();
    }

    @Transactional(readOnly = true)
    public List<BankStatementResult> bankDepositExceptions(int limit, Caller caller) {
        if (!caller.grants().holds(FINANCE_READ) || limit < 1 || limit > 1000) {
            throw forbidden(FINANCE_READ);
        }
        return jdbc.sql("select * from bank_statement_line where status='EXCEPTION' order by received_at limit :n")
                .param("n", limit).query(FinanceOperations::mapBankStatement).list();
    }

    @Transactional
    public BankStatementResult resolveBankDepositException(UUID id, UUID depositId, String reason, Caller caller) {
        if (!caller.grants().holds(FINANCE_RESOLVE)) {
            throw forbidden(FINANCE_RESOLVE);
        }
        BankStatementResult line = jdbc.sql("select * from bank_statement_line where id=:id for update").param("id", id)
                .query(FinanceOperations::mapBankStatement).optional().orElseThrow(() -> new DomainException(
                        DomainException.Kind.NOT_FOUND, "BANK_STATEMENT_NOT_FOUND", "statement line " + id + " not found",
                        List.of("FN-021", "FN-041")));
        if (!"EXCEPTION".equals(line.status())) {
            throw DomainException.conflict("BANK_STATEMENT_RESOLVED", "statement line is already reconciled",
                    List.of("FN-041"));
        }
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3 || why.length() > 500) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("FN-041"));
        }
        BankDeposit deposit = jdbc.sql("select * from bank_deposit where id=:id and status='OPEN' for update")
                .param("id", depositId).query(FinanceOperations::mapBankDeposit).optional().orElseThrow(() ->
                        DomainException.validation("BANK_DEPOSIT_MISMATCH", "deposit must be open for reconciliation",
                                List.of("FN-041")));
        requireFinanceRegion(caller, FINANCE_RESOLVE, deposit.region());
        if (!line.externalRef().equals(deposit.depositRef()) || !line.bank().equals(deposit.bank())
                || !line.region().equals(deposit.region()) || !line.currency().equals(deposit.currency())
                || line.amountMinor() != deposit.totalMinor()) {
            throw DomainException.validation("BANK_DEPOSIT_MISMATCH", "statement line and deposit fields differ",
                    List.of("FN-041"));
        }
        long liveReceiptTotal = jdbc.sql("select coalesce(sum(dr.amount_minor),0) from bank_deposit_receipt dr "
                        + "join receipt r on r.id=dr.receipt_id where dr.deposit_id=:id and r.status='ISSUED'")
                .param("id", depositId).query(Long.class).single();
        if (liveReceiptTotal != deposit.totalMinor()) {
            throw DomainException.validation("BANK_DEPOSIT_RECEIPTS_CHANGED",
                    "deposit receipts must remain issued and retain their recorded total", List.of("FN-021", "FN-041"));
        }
        Instant now = clock.instant();
        jdbc.sql("update bank_statement_line set deposit_id=:deposit,status='RESOLVED',reason=:reason,"
                        + "resolved_by=:by,resolved_at=:at where id=:id")
                .param("deposit", depositId).param("reason", why).param("by", caller.actor().id())
                .param("at", Timestamp.from(now)).param("id", id).update();
        jdbc.sql("update bank_deposit set status='MATCHED',matched_by=:by,matched_at=:at where id=:id")
                .param("by", caller.actor().id()).param("at", Timestamp.from(now)).param("id", depositId).update();
        audit.record("BANK_DEPOSIT_EXCEPTION_RESOLVED", "BankDeposit", depositId.toString(),
                Map.of("status", "OPEN"), Map.of("status", "MATCHED", "statementLineId", id.toString()), why);
        return jdbc.sql("select * from bank_statement_line where id=:id").param("id", id)
                .query(FinanceOperations::mapBankStatement).single();
    }

    private record DepositReceipt(UUID id, String number, String region, String currency, long amountMinor,
                                  String status) { }

    private static BankDeposit mapBankDeposit(ResultSet rs, int row) throws SQLException {
        Timestamp matched = rs.getTimestamp("matched_at");
        return new BankDeposit(rs.getObject("id", UUID.class), rs.getString("deposit_ref"), rs.getString("bank"),
                rs.getString("region"), rs.getString("currency"), rs.getLong("total_minor"), rs.getString("status"),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                matched == null ? null : matched.toInstant(), rs.getString("reason"));
    }

    private static BankStatementResult mapBankStatement(ResultSet rs, int row) throws SQLException {
        Timestamp received = rs.getTimestamp("received_at");
        return new BankStatementResult(rs.getObject("id", UUID.class), rs.getString("source"),
                rs.getString("external_ref"), rs.getString("bank"), rs.getString("region"), rs.getString("currency"),
                rs.getLong("amount_minor"), rs.getObject("deposit_id", UUID.class), rs.getString("status"),
                rs.getString("reason"), received.toInstant());
    }

    private static void requireFinanceRegion(Caller caller, String permission, String region) {
        if (!caller.grants().allowsInRegion(permission, region)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "FINANCE_REGION_REQUIRED",
                    "finance permission is required in region " + region, List.of("FN-021", "FN-041"));
        }
    }

    @Transactional
    public Refund requestRefund(UUID paymentId, long amountMinor, String reason, Caller caller) {
        if (!caller.grants().holds(REFUND_REQUEST)) {
            throw forbidden(REFUND_REQUEST);
        }
        if (amountMinor < 1 || reason == null || reason.isBlank() || reason.length() > 500) {
            throw DomainException.validation("REFUND_INVALID", "refund amount and reason are required",
                    List.of("FN-012"));
        }
        PaymentContext p = jdbc.sql("""
                select p.id, p.status, p.amount_minor, p.currency, p.region, p.method, p.gateway_txn_id, r.number
                from payment p join receipt r on r.payment_id=p.id
                where p.id=:id for update of p
                """).param("id", paymentId).query(FinanceOperations::payment).optional().orElseThrow(() ->
                new DomainException(DomainException.Kind.NOT_FOUND, "PAYMENT_NOT_FOUND", "payment " + paymentId
                        + " has no receipt", List.of("FN-012")));
        if (!"CONFIRMED".equals(p.status())) {
            throw DomainException.conflict("PAYMENT_NOT_CONFIRMED", "only a confirmed payment can be refunded",
                    List.of("FN-012"));
        }
        Long requested = jdbc.sql("select coalesce(sum(amount_minor),0) from refund_request where payment_id=:id"
                        + " and status in ('PENDING','APPROVED','SUBMITTED')")
                .param("id", paymentId).query(Long.class).single();
        if (amountMinor > p.amountMinor() - requested) {
            throw DomainException.validation("REFUND_EXCEEDS_PAID", "refunds exceed the remaining paid amount",
                    List.of("FN-012"), p.currency());
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                insert into refund_request (id,payment_id,receipt_number,region,currency,amount_minor,reason,status,
                    requested_by,requested_at) values (:id,:payment,:receipt,:region,:currency,:amount,:reason,
                    'PENDING',:by,:at)
                """).param("id", id).param("payment", paymentId).param("receipt", p.receiptNumber())
                .param("region", p.region()).param("currency", p.currency()).param("amount", amountMinor)
                .param("reason", reason.strip()).param("by", caller.actor().id()).param("at", Timestamp.from(now))
                .update();
        audit.record("REFUND_REQUESTED", "Payment", paymentId.toString(), null,
                Map.of("refundId", id.toString(), "amountMinor", amountMinor, "currency", p.currency()), reason);
        return refund(id);
    }

    @Transactional
    public Refund decideRefund(UUID id, boolean approve, String reason, Caller caller) {
        if (!caller.grants().holds(REFUND_APPROVE)) {
            throw forbidden(REFUND_APPROVE);
        }
        Refund current = jdbc.sql("select * from refund_request where id=:id for update").param("id", id)
                .query(FinanceOperations::mapRefund).optional().orElseThrow(() -> new DomainException(
                        DomainException.Kind.NOT_FOUND, "REFUND_NOT_FOUND", "refund " + id + " not found",
                        List.of("FN-012")));
        if (!"PENDING".equals(current.status())) {
            throw DomainException.conflict("REFUND_DECIDED", "refund is already " + current.status(),
                    List.of("FN-012"));
        }
        if (current.requestedBy().equals(caller.actor().id())) {
            throw new DomainException(DomainException.Kind.SEGREGATION_OF_DUTIES, "SAME_MAKER_CHECKER",
                    "the refund maker cannot decide the refund", List.of("FN-012", "CC-002"));
        }
        var auth = actors.authorisation();
        long limit = auth.approvalLimit("REFUND_AMOUNT", current.currency(),
                new ScopeTarget(current.region(), null, null)).orElse(-1);
        if (approve && (limit < 0 || current.amountMinor() > limit)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "APPROVAL_LIMIT_EXCEEDED",
                    "the refund exceeds the caller's approval limit", List.of("FN-012", "CC-003"), limit);
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw DomainException.validation("REASON_INVALID", "a reason is required", List.of("FN-012"));
        }
        Instant now = clock.instant();
        String status = approve ? "APPROVED" : "REJECTED";
        String remoteRef = null;
        if (approve) {
            PaymentContext payment = jdbc.sql("select p.status,p.amount_minor,p.currency,p.region,p.method,"
                            + "p.gateway_txn_id,r.number from payment p join receipt r on r.payment_id=p.id where p.id=:id")
                    .param("id", current.paymentId()).query(FinanceOperations::payment).single();
            if (onlineMethod(payment.method())) {
                remoteRef = gateway.refund(current.paymentId(), payment.gatewayTxnId(), id.toString(),
                        current.amountMinor(), current.currency());
                status = "SUBMITTED";
            }
        }
        jdbc.sql("update refund_request set status=:status, decided_by=:by, decided_at=:at, remote_ref=:remote "
                        + "where id=:id")
                .param("status", status).param("by", caller.actor().id()).param("at", Timestamp.from(now))
                .param("remote", remoteRef).param("id", id).update();
        if (approve) {
            events.record("itms.payment.refund-approved", id.toString(), Map.of("refundId", id.toString(),
                    "paymentId", current.paymentId().toString(), "receiptNumber", current.receiptNumber(),
                    "amountMinor", current.amountMinor(), "currency", current.currency(), "status", status,
                    "remoteRef", remoteRef == null ? "" : remoteRef));
        }
        audit.record("REFUND_" + status, "Refund", id.toString(), Map.of("status", "PENDING"),
                Map.of("status", status), reason);
        return refund(id);
    }

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
            if (line.source() == null || !line.source().matches("^[A-Z][A-Z0-9_]{1,39}$")
                    || line.externalRef() == null || line.externalRef().isBlank() || line.externalRef().length() > 200
                    || line.region() == null || line.currency() == null || !line.currency().matches("^[A-Z]{3}$")
                    || line.amountMinor() < 0) {
                throw DomainException.validation("SETTLEMENT_LINE_INVALID", "settlement row is malformed",
                        List.of("FN-006", "FN-040"));
            }
            UUID match = jdbc.sql("select id from payment where gateway_txn_id=:ref and amount_minor=:amount"
                            + " and currency=:currency and region=:region and status='CONFIRMED'")
                    .param("ref", line.externalRef()).param("amount", line.amountMinor())
                    .param("currency", line.currency()).param("region", line.region())
                    .query(UUID.class).optional().orElse(null);
            String status = match == null ? "EXCEPTION" : "MATCHED";
            jdbc.sql("""
                    insert into reconciliation_item (id,source,external_ref,paid_at,region,currency,amount_minor,
                        payment_id,status,reason,received_at)
                    values (:id,:source,:ref,:paid,:region,:currency,:amount,:payment,:status,:reason,:received)
                    on conflict (source,external_ref) do nothing
                    """).param("id", UUID.randomUUID()).param("source", line.source())
                    .param("ref", line.externalRef()).param("paid", line.paidAt()).param("region", line.region())
                    .param("currency", line.currency()).param("amount", line.amountMinor()).param("payment", match)
                    .param("status", status).param("reason", match == null ? "No matching confirmed payment" : null)
                    .param("received", Timestamp.from(clock.instant())).update();
        }
        return jdbc.sql("select * from reconciliation_item where source=:source order by received_at desc limit :n")
                .param("source", lines.getFirst().source()).param("n", lines.size())
                .query(FinanceOperations::mapReconciliation).list();
    }

    @Transactional(readOnly = true)
    public List<Reconciliation> exceptions(int limit, Caller caller) {
        if (!caller.grants().holds(FINANCE_READ) || limit < 1 || limit > 1000) {
            throw forbidden(FINANCE_READ);
        }
        return jdbc.sql("select * from reconciliation_item where status='EXCEPTION' order by received_at limit :n")
                .param("n", limit).query(FinanceOperations::mapReconciliation).list();
    }

    @Transactional
    public Reconciliation resolve(UUID id, UUID paymentId, String reason, Caller caller) {
        if (!caller.grants().holds(FINANCE_RESOLVE)) {
            throw forbidden(FINANCE_RESOLVE);
        }
        Reconciliation item = jdbc.sql("select * from reconciliation_item where id=:id for update").param("id", id)
                .query(FinanceOperations::mapReconciliation).optional().orElseThrow(() -> new DomainException(
                        DomainException.Kind.NOT_FOUND, "RECONCILIATION_NOT_FOUND", "reconciliation item " + id
                        + " not found", List.of("FN-040", "FN-041")));
        if (!"EXCEPTION".equals(item.status())) {
            throw DomainException.conflict("RECONCILIATION_RESOLVED", "item is already " + item.status(),
                    List.of("FN-041"));
        }
        PaymentContext payment = jdbc.sql("select p.status,p.amount_minor,p.currency,p.region,p.method,"
                        + "p.gateway_txn_id,r.number"
                        + " from payment p join receipt r on r.payment_id=p.id where p.id=:id")
                .param("id", paymentId).query(FinanceOperations::payment).optional().orElseThrow(() ->
                        new DomainException(DomainException.Kind.NOT_FOUND, "PAYMENT_NOT_FOUND",
                                "payment " + paymentId + " has no receipt", List.of("FN-041")));
        if (!"CONFIRMED".equals(payment.status()) || !item.externalRef().equals(payment.gatewayTxnId())
                || payment.amountMinor() != item.amountMinor()
                || !payment.currency().equals(item.currency()) || !payment.region().equals(item.region())) {
            throw DomainException.validation("RECONCILIATION_MISMATCH", "payment amount, currency or region differs",
                    List.of("FN-040", "FN-041"));
        }
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3 || why.length() > 500) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("FN-041"));
        }
        jdbc.sql("update reconciliation_item set payment_id=:payment,status='RESOLVED',reason=:reason,"
                        + "resolved_by=:by,resolved_at=:at where id=:id")
                .param("payment", paymentId).param("reason", why).param("by", caller.actor().id())
                .param("at", Timestamp.from(clock.instant())).param("id", id).update();
        audit.record("RECONCILIATION_EXCEPTION_RESOLVED", "ReconciliationItem", id.toString(),
                Map.of("status", "EXCEPTION"), Map.of("status", "RESOLVED", "paymentId", paymentId.toString()), why);
        return jdbc.sql("select * from reconciliation_item where id=:id").param("id", id)
                .query(FinanceOperations::mapReconciliation).single();
    }

    @Transactional(readOnly = true)
    public Refund refund(UUID id) {
        return jdbc.sql("select * from refund_request where id=:id").param("id", id)
                .query(FinanceOperations::mapRefund).single();
    }

    private record PaymentContext(String status, long amountMinor, String currency, String region, String method,
                                  String gatewayTxnId, String receiptNumber) { }

    private static PaymentContext payment(ResultSet rs, int row) throws SQLException {
        return new PaymentContext(rs.getString("status"), rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getString("region"), rs.getString("method"), rs.getString("gateway_txn_id"),
                rs.getString("number"));
    }

    private static Till mapTill(ResultSet rs, int row) throws SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new Till(rs.getObject("id", UUID.class), rs.getString("cashier_id"), rs.getString("region"),
                rs.getLong("opening_amount_minor"), rs.getTimestamp("opened_at").toInstant(),
                rs.getString("status"), closedAt == null ? null : closedAt.toInstant());
    }

    private void requireTillScope(Caller caller, String region) {
        if (!caller.grants().allowsInRegion(TILL_MANAGE, region)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "TILL_FORBIDDEN",
                    "till permission is required in region " + region, List.of("FN-020"));
        }
    }

    private static boolean onlineMethod(String method) {
        return "GATEWAY_CARD".equals(method) || "E_PAYMENT".equals(method) || "MOBILE_WALLET".equals(method);
    }

    private static Refund mapRefund(ResultSet rs, int row) throws SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new Refund(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getString("receipt_number"), rs.getString("region"), rs.getString("currency"),
                rs.getLong("amount_minor"), rs.getString("reason"), rs.getString("status"),
                rs.getString("requested_by"), rs.getTimestamp("requested_at").toInstant(), rs.getString("decided_by"),
                decided == null ? null : decided.toInstant(), rs.getString("remote_ref"));
    }

    private static Reconciliation mapReconciliation(ResultSet rs, int row) throws SQLException {
        Timestamp paid = rs.getTimestamp("paid_at");
        return new Reconciliation(rs.getObject("id", UUID.class), rs.getString("source"),
                rs.getString("external_ref"), paid == null ? null : paid.toInstant(), rs.getString("region"),
                rs.getString("currency"), rs.getLong("amount_minor"), rs.getObject("payment_id", UUID.class),
                rs.getString("status"), rs.getString("reason"), rs.getTimestamp("received_at").toInstant());
    }

    private static DomainException forbidden(String permission) {
        return new DomainException(DomainException.Kind.OUT_OF_SCOPE, "FINANCE_FORBIDDEN",
                "finance permission is required: " + permission, List.of("FN-012", "FN-040", "FN-041"));
    }
}

package gov.itms.payment.infrastructure;

import gov.itms.payment.application.FinanceOperations.BankDeposit;
import gov.itms.payment.application.FinanceOperations.BankStatementLine;
import gov.itms.payment.application.FinanceOperations.BankStatementResult;
import gov.itms.payment.application.FinanceOperations.Reconciliation;
import gov.itms.payment.application.FinanceOperations.Refund;
import gov.itms.payment.application.FinanceOperations.SettlementLine;
import gov.itms.payment.application.FinanceOperations.Till;
import gov.itms.payment.application.FinanceOperations.TillTotal;
import gov.itms.payment.application.FinanceRepository;
import gov.itms.payment.domain.Payment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Tills (V6), refunds, reconciliation and ledger (V4), bank deposits (V7). */
class JdbcFinanceRepository implements FinanceRepository {

    private static final String PAYMENT = """
            select p.status, p.amount_minor, p.currency, p.region, p.method, p.gateway_txn_id, r.number
            from payment p join receipt r on r.payment_id = p.id where p.id = :id
            """;

    private final JdbcClient jdbc;

    JdbcFinanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- tills

    @Override
    public boolean insertTill(Till t) {
        return jdbc.sql("insert into cashier_till (id,cashier_id,region,opening_amount_minor,opened_at,status) "
                        + "values (:id,:cashier,:region,:opening,:at,:status) on conflict do nothing")
                .param("id", t.id()).param("cashier", t.cashierId()).param("region", t.region())
                .param("opening", t.openingAmountMinor()).param("at", Timestamp.from(t.openedAt()))
                .param("status", t.status()).update() > 0;
    }

    @Override
    public Optional<Till> lockTill(UUID id) {
        return jdbc.sql("select id,cashier_id,region,opening_amount_minor,opened_at,status,closed_at "
                        + "from cashier_till where id=:id for update")
                .param("id", id).query(JdbcFinanceRepository::mapTill).optional();
    }

    @Override
    public Map<Payment.Method, Long> tillTakings(UUID tillId) {
        Map<Payment.Method, Long> takings = new EnumMap<>(Payment.Method.class);
        jdbc.sql("""
                select t.method, sum(t.amount_minor) as amount
                from till_payment t join receipt r on r.id=t.receipt_id
                where t.till_id=:id and r.status='ISSUED' group by t.method
                """).param("id", tillId).query((rs, row) -> takings.put(Payment.Method.valueOf(rs.getString("method")),
                rs.getLong("amount"))).list();
        return takings;
    }

    @Override
    public void insertTillTotal(UUID tillId, TillTotal total) {
        jdbc.sql("insert into till_reconciliation (till_id,method,expected_minor,actual_minor,difference_minor) "
                        + "values (:till,:method,:expected,:actual,:difference)")
                .param("till", tillId).param("method", total.method().name()).param("expected", total.expectedMinor())
                .param("actual", total.actualMinor()).param("difference", total.differenceMinor()).update();
    }

    @Override
    public void closeTill(UUID id, String by, Instant at, String reason) {
        jdbc.sql("update cashier_till set status='CLOSED',closed_by=:by,closed_at=:at,closing_reason=:reason "
                        + "where id=:id")
                .param("by", by).param("at", Timestamp.from(at)).param("reason", reason).param("id", id).update();
    }

    // ---- bank deposits

    @Override
    public List<DepositReceipt> lockReceipts(List<String> numbers) {
        return jdbc.sql("select id,number,region,currency,total_minor,status from receipt "
                        + "where number in (:numbers) order by number for update")
                .param("numbers", numbers).query((rs, row) -> new DepositReceipt(rs.getObject("id", UUID.class),
                        rs.getString("number"), rs.getString("region"), rs.getString("currency"),
                        rs.getLong("total_minor"), rs.getString("status"))).list();
    }

    @Override
    public boolean insertDeposit(BankDeposit d, List<DepositReceipt> receipts) {
        int inserted = jdbc.sql("insert into bank_deposit (id,deposit_ref,bank,region,currency,total_minor,status,"
                        + "created_by,created_at) values (:id,:ref,:bank,:region,:currency,:total,:status,:by,:at) "
                        + "on conflict do nothing")
                .param("id", d.id()).param("ref", d.depositRef()).param("bank", d.bank()).param("region", d.region())
                .param("currency", d.currency()).param("total", d.totalMinor()).param("status", d.status())
                .param("by", d.createdBy()).param("at", Timestamp.from(d.createdAt())).update();
        if (inserted == 0) {
            return false;
        }
        for (DepositReceipt receipt : receipts) {
            jdbc.sql("insert into bank_deposit_receipt (deposit_id,receipt_id,amount_minor) "
                            + "values (:deposit,:receipt,:amount)")
                    .param("deposit", d.id()).param("receipt", receipt.id()).param("amount", receipt.amountMinor())
                    .update();
        }
        return true;
    }

    @Override
    public BankDeposit deposit(UUID id) {
        return jdbc.sql("select * from bank_deposit where id=:id").param("id", id)
                .query(JdbcFinanceRepository::mapBankDeposit).single();
    }

    @Override
    public Optional<BankDeposit> lockMatchingDeposit(BankStatementLine line) {
        return jdbc.sql("select * from bank_deposit where deposit_ref=:ref and bank=:bank "
                        + "and region=:region and currency=:currency and total_minor=:amount and status='OPEN' "
                        + "and (select count(*) from bank_deposit_receipt dr join receipt r on r.id=dr.receipt_id "
                        + "where dr.deposit_id=bank_deposit.id and r.status='ISSUED') = "
                        + "(select count(*) from bank_deposit_receipt dr where dr.deposit_id=bank_deposit.id) for update")
                .param("ref", line.externalRef()).param("bank", line.bank()).param("region", line.region())
                .param("currency", line.currency()).param("amount", line.amountMinor())
                .query(JdbcFinanceRepository::mapBankDeposit).optional();
    }

    @Override
    public Optional<BankDeposit> lockOpenDeposit(UUID id) {
        return jdbc.sql("select * from bank_deposit where id=:id and status='OPEN' for update")
                .param("id", id).query(JdbcFinanceRepository::mapBankDeposit).optional();
    }

    @Override
    public long issuedReceiptTotal(UUID depositId) {
        return jdbc.sql("select coalesce(sum(dr.amount_minor),0) from bank_deposit_receipt dr "
                        + "join receipt r on r.id=dr.receipt_id where dr.deposit_id=:id and r.status='ISSUED'")
                .param("id", depositId).query(Long.class).single();
    }

    @Override
    public void markDepositMatched(UUID id, String by, Instant at) {
        jdbc.sql("update bank_deposit set status='MATCHED',matched_by=:by,matched_at=:at where id=:id")
                .param("by", by).param("at", Timestamp.from(at)).param("id", id).update();
    }

    @Override
    public void insertStatementLine(BankStatementLine line, UUID depositId, String status, String reason, Instant at) {
        jdbc.sql("insert into bank_statement_line (id,source,external_ref,bank,region,currency,amount_minor,"
                        + "booked_at,deposit_id,status,reason,received_at) values (:id,:source,:ref,:bank,:region,"
                        + ":currency,:amount,:booked,:deposit,:status,:reason,:received) "
                        + "on conflict (source,external_ref) do nothing")
                .param("id", UUID.randomUUID()).param("source", line.source()).param("ref", line.externalRef())
                .param("bank", line.bank()).param("region", line.region()).param("currency", line.currency())
                .param("amount", line.amountMinor())
                .param("booked", line.bookedAt() == null ? null : Timestamp.from(line.bookedAt()))
                .param("deposit", depositId).param("status", status).param("reason", reason)
                .param("received", Timestamp.from(at)).update();
    }

    @Override
    public List<BankStatementResult> latestStatementLines(String source, int limit) {
        return jdbc.sql("select * from bank_statement_line where source=:source order by received_at desc limit :n")
                .param("source", source).param("n", limit).query(JdbcFinanceRepository::mapBankStatement).list();
    }

    @Override
    public List<BankStatementResult> statementExceptions(int limit) {
        return jdbc.sql("select * from bank_statement_line where status='EXCEPTION' order by received_at limit :n")
                .param("n", limit).query(JdbcFinanceRepository::mapBankStatement).list();
    }

    @Override
    public Optional<BankStatementResult> lockStatementLine(UUID id) {
        return jdbc.sql("select * from bank_statement_line where id=:id for update").param("id", id)
                .query(JdbcFinanceRepository::mapBankStatement).optional();
    }

    @Override
    public void resolveStatementLine(UUID id, UUID depositId, String reason, String by, Instant at) {
        jdbc.sql("update bank_statement_line set deposit_id=:deposit,status='RESOLVED',reason=:reason,"
                        + "resolved_by=:by,resolved_at=:at where id=:id")
                .param("deposit", depositId).param("reason", reason).param("by", by)
                .param("at", Timestamp.from(at)).param("id", id).update();
    }

    @Override
    public BankStatementResult statementLine(UUID id) {
        return jdbc.sql("select * from bank_statement_line where id=:id").param("id", id)
                .query(JdbcFinanceRepository::mapBankStatement).single();
    }

    // ---- refunds

    @Override
    public Optional<PaymentContext> payment(UUID paymentId, boolean lock) {
        return jdbc.sql(PAYMENT + (lock ? " for update of p" : "")).param("id", paymentId)
                .query(JdbcFinanceRepository::mapPayment).optional();
    }

    @Override
    public long openRefundTotal(UUID paymentId) {
        return jdbc.sql("select coalesce(sum(amount_minor),0) from refund_request where payment_id=:id"
                        + " and status in ('PENDING','APPROVED','SUBMITTED')")
                .param("id", paymentId).query(Long.class).single();
    }

    @Override
    public void insertRefund(Refund r) {
        jdbc.sql("""
                insert into refund_request (id,payment_id,receipt_number,region,currency,amount_minor,reason,status,
                    requested_by,requested_at) values (:id,:payment,:receipt,:region,:currency,:amount,:reason,
                    :status,:by,:at)
                """).param("id", r.id()).param("payment", r.paymentId()).param("receipt", r.receiptNumber())
                .param("region", r.region()).param("currency", r.currency()).param("amount", r.amountMinor())
                .param("reason", r.reason()).param("status", r.status()).param("by", r.requestedBy())
                .param("at", Timestamp.from(r.requestedAt())).update();
    }

    @Override
    public Optional<Refund> lockRefund(UUID id) {
        return jdbc.sql("select * from refund_request where id=:id for update").param("id", id)
                .query(JdbcFinanceRepository::mapRefund).optional();
    }

    @Override
    public void decideRefund(UUID id, String status, String by, Instant at, String remoteRef) {
        jdbc.sql("update refund_request set status=:status, decided_by=:by, decided_at=:at, remote_ref=:remote "
                        + "where id=:id")
                .param("status", status).param("by", by).param("at", Timestamp.from(at))
                .param("remote", remoteRef).param("id", id).update();
    }

    @Override
    public Refund refund(UUID id) {
        return jdbc.sql("select * from refund_request where id=:id").param("id", id)
                .query(JdbcFinanceRepository::mapRefund).single();
    }

    // ---- settlement reconciliation

    @Override
    public Optional<UUID> matchingPayment(SettlementLine line) {
        return jdbc.sql("select id from payment where gateway_txn_id=:ref and amount_minor=:amount"
                        + " and currency=:currency and region=:region and status='CONFIRMED'")
                .param("ref", line.externalRef()).param("amount", line.amountMinor())
                .param("currency", line.currency()).param("region", line.region())
                .query(UUID.class).optional();
    }

    @Override
    public void insertReconciliation(SettlementLine line, UUID paymentId, String status, String reason, Instant at) {
        jdbc.sql("""
                insert into reconciliation_item (id,source,external_ref,paid_at,region,currency,amount_minor,
                    payment_id,status,reason,received_at)
                values (:id,:source,:ref,:paid,:region,:currency,:amount,:payment,:status,:reason,:received)
                on conflict (source,external_ref) do nothing
                """).param("id", UUID.randomUUID()).param("source", line.source())
                .param("ref", line.externalRef())
                .param("paid", line.paidAt() == null ? null : Timestamp.from(line.paidAt()))
                .param("region", line.region()).param("currency", line.currency())
                .param("amount", line.amountMinor()).param("payment", paymentId)
                .param("status", status).param("reason", reason).param("received", Timestamp.from(at)).update();
    }

    @Override
    public List<Reconciliation> latestReconciliations(String source, int limit) {
        return jdbc.sql("select * from reconciliation_item where source=:source order by received_at desc limit :n")
                .param("source", source).param("n", limit).query(JdbcFinanceRepository::mapReconciliation).list();
    }

    @Override
    public List<Reconciliation> reconciliationExceptions(int limit) {
        return jdbc.sql("select * from reconciliation_item where status='EXCEPTION' order by received_at limit :n")
                .param("n", limit).query(JdbcFinanceRepository::mapReconciliation).list();
    }

    @Override
    public Optional<Reconciliation> lockReconciliation(UUID id) {
        return jdbc.sql("select * from reconciliation_item where id=:id for update").param("id", id)
                .query(JdbcFinanceRepository::mapReconciliation).optional();
    }

    @Override
    public void resolveReconciliation(UUID id, UUID paymentId, String reason, String by, Instant at) {
        jdbc.sql("update reconciliation_item set payment_id=:payment,status='RESOLVED',reason=:reason,"
                        + "resolved_by=:by,resolved_at=:at where id=:id")
                .param("payment", paymentId).param("reason", reason).param("by", by)
                .param("at", Timestamp.from(at)).param("id", id).update();
    }

    @Override
    public Reconciliation reconciliation(UUID id) {
        return jdbc.sql("select * from reconciliation_item where id=:id").param("id", id)
                .query(JdbcFinanceRepository::mapReconciliation).single();
    }

    // ---- mapping

    private static PaymentContext mapPayment(ResultSet rs, int row) throws SQLException {
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

    private static BankDeposit mapBankDeposit(ResultSet rs, int row) throws SQLException {
        Timestamp matched = rs.getTimestamp("matched_at");
        return new BankDeposit(rs.getObject("id", UUID.class), rs.getString("deposit_ref"), rs.getString("bank"),
                rs.getString("region"), rs.getString("currency"), rs.getLong("total_minor"), rs.getString("status"),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                matched == null ? null : matched.toInstant(), rs.getString("reason"));
    }

    private static BankStatementResult mapBankStatement(ResultSet rs, int row) throws SQLException {
        return new BankStatementResult(rs.getObject("id", UUID.class), rs.getString("source"),
                rs.getString("external_ref"), rs.getString("bank"), rs.getString("region"), rs.getString("currency"),
                rs.getLong("amount_minor"), rs.getObject("deposit_id", UUID.class), rs.getString("status"),
                rs.getString("reason"), rs.getTimestamp("received_at").toInstant());
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
}

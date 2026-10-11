package gov.itms.payment.application;

import gov.itms.payment.application.FinanceOperations.BankDeposit;
import gov.itms.payment.application.FinanceOperations.BankStatementLine;
import gov.itms.payment.application.FinanceOperations.BankStatementResult;
import gov.itms.payment.application.FinanceOperations.Reconciliation;
import gov.itms.payment.application.FinanceOperations.Refund;
import gov.itms.payment.application.FinanceOperations.SettlementLine;
import gov.itms.payment.application.FinanceOperations.Till;
import gov.itms.payment.application.FinanceOperations.TillTotal;
import gov.itms.payment.domain.Payment;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Storage for tills, bank deposits, refunds and settlement reconciliation (FN-006, FN-012, FN-020, FN-021, FN-040). */
public interface FinanceRepository {

    /** A confirmed or other payment with its receipt number. */
    record PaymentContext(String status, long amountMinor, String currency, String region, String method,
                          String gatewayTxnId, String receiptNumber) { }

    /** A receipt considered for a bank deposit group. */
    record DepositReceipt(UUID id, String number, String region, String currency, long amountMinor, String status) { }

    // ---- tills (FN-020)

    /** False when the cashier already has an open till in the region. */
    boolean insertTill(Till till);

    Optional<Till> lockTill(UUID id);

    /** Issued receipts taken at the till, summed per method. */
    Map<Payment.Method, Long> tillTakings(UUID tillId);

    void insertTillTotal(UUID tillId, TillTotal total);

    void closeTill(UUID id, String by, Instant at, String reason);

    // ---- bank deposits (FN-021)

    List<DepositReceipt> lockReceipts(List<String> numbers);

    /** False when the deposit reference is already recorded. */
    boolean insertDeposit(BankDeposit deposit, List<DepositReceipt> receipts);

    BankDeposit deposit(UUID id);

    /** The open deposit matching the statement line exactly whose receipts are all still issued. */
    Optional<BankDeposit> lockMatchingDeposit(BankStatementLine line);

    Optional<BankDeposit> lockOpenDeposit(UUID id);

    long issuedReceiptTotal(UUID depositId);

    void markDepositMatched(UUID id, String by, Instant at);

    /** Ignored when the source already sent this reference. */
    void insertStatementLine(BankStatementLine line, UUID depositId, String status, String reason, Instant at);

    List<BankStatementResult> latestStatementLines(String source, int limit);

    List<BankStatementResult> statementExceptions(int limit);

    Optional<BankStatementResult> lockStatementLine(UUID id);

    void resolveStatementLine(UUID id, UUID depositId, String reason, String by, Instant at);

    BankStatementResult statementLine(UUID id);

    // ---- refunds (FN-012)

    Optional<PaymentContext> payment(UUID paymentId, boolean lock);

    /** Sum of refunds that are pending, approved or submitted for the payment. */
    long openRefundTotal(UUID paymentId);

    void insertRefund(Refund refund);

    Optional<Refund> lockRefund(UUID id);

    void decideRefund(UUID id, String status, String by, Instant at, String remoteRef);

    Refund refund(UUID id);

    // ---- settlement reconciliation (FN-006, FN-040, FN-041)

    Optional<UUID> matchingPayment(SettlementLine line);

    /** Ignored when the source already sent this reference. */
    void insertReconciliation(SettlementLine line, UUID paymentId, String status, String reason, Instant at);

    List<Reconciliation> latestReconciliations(String source, int limit);

    List<Reconciliation> reconciliationExceptions(int limit);

    Optional<Reconciliation> lockReconciliation(UUID id);

    void resolveReconciliation(UUID id, UUID paymentId, String reason, String by, Instant at);

    Reconciliation reconciliation(UUID id);
}

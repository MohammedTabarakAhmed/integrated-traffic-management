package gov.itms.transaction.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.transaction.domain.DomainException;
import gov.itms.transaction.domain.StatusChange;
import gov.itms.transaction.domain.Transaction;
import gov.itms.transaction.domain.TransactionCursor;
import gov.itms.transaction.domain.TransactionReference;
import gov.itms.transaction.domain.TransactionStatus;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in customer tracks their own transactions, whatever channel opened them, by reference number (CC-025,
 * ADR-067 d). "Their own" means the applicant is the traffic file traffic-file-service finds for the national id in
 * the customer's token; a reference of anyone else's transaction answers as not found, so references cannot be probed
 * (CC-001). Every view is audited like a staff view (CC-120).
 */
public class CustomerTransactions {

    /** Held only through the implicit CUSTOMER role (iam V59). */
    public static final String CUSTOMER_READ = "customer.transaction.read";

    private final TransactionRepository transactions;
    private final OwnFile ownFile;
    private final AuditRecorder audit;

    public CustomerTransactions(TransactionRepository transactions, OwnFile ownFile, AuditRecorder audit) {
        this.transactions = transactions;
        this.ownFile = ownFile;
        this.audit = audit;
    }

    /** The customer's transactions, newest first; {@code nextCursor} is {@code null} on the last page. */
    @Transactional
    public TransactionService.TransactionPage list(TransactionStatus status, String cursor, Integer limit,
                                                   Caller caller) {
        require(caller);
        int size = limit == null ? TransactionService.DEFAULT_PAGE : limit;
        if (size < 1 || size > TransactionService.MAX_PAGE) {
            throw new DomainException(DomainException.Kind.VALIDATION, "LIMIT_INVALID",
                    "limit must be between 1 and " + TransactionService.MAX_PAGE, List.of("CC-025"),
                    TransactionService.MAX_PAGE);
        }
        String applicant = fileNumber();
        TransactionCursor after = cursor == null ? null : TransactionCursor.decode(cursor);
        List<Transaction> rows = transactions.findByApplicant(applicant, status, after, size + 1);
        List<Transaction> page = rows.subList(0, Math.min(size, rows.size()));
        String next = rows.size() > size ? TransactionCursor.after(page.get(size - 1)).encode() : null;
        audit.record("TRANSACTION_LIST_VIEWED", TransactionService.ENTITY_LIST, applicant, null,
                Map.of("viewer", "OWNER", "references", page.stream().map(t -> t.reference().toString()).toList()),
                null);
        return new TransactionService.TransactionPage(page, next);
    }

    /** One of the customer's transactions by reference (CC-025). */
    @Transactional
    public Transaction get(String reference, Caller caller) {
        Transaction transaction = own(reference, caller);
        audit.record("TRANSACTION_VIEWED", TransactionService.ENTITY, reference, null, Map.of("viewer", "OWNER"),
                null);
        return transaction;
    }

    /** Every status change of one of the customer's transactions, oldest first (CC-023, CC-025). */
    @Transactional
    public List<StatusChange> history(String reference, Caller caller) {
        Transaction transaction = own(reference, caller);
        audit.record("TRANSACTION_HISTORY_VIEWED", TransactionService.ENTITY, reference, null,
                Map.of("viewer", "OWNER"), null);
        return transactions.history(transaction.id());
    }

    private Transaction own(String reference, Caller caller) {
        require(caller);
        String applicant = fileNumber();
        return transactions.find(TransactionReference.parse(reference))
                .filter(t -> t.applicantFileNumber().equals(applicant))
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                        "there is no transaction with this reference", List.of("CC-021")));
    }

    private String fileNumber() {
        return ownFile.fileNumber().orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                "OWN_FILE_NOT_FOUND", "you have no traffic file yet", List.of("DL-001")));
    }

    private static void require(Caller caller) {
        // The customer grant has no scopes: only an unscoped check (no region, no module) can match it.
        if (!caller.scopes().allows(CUSTOMER_READ, null, null)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not read your transactions here", List.of("CC-001"));
        }
    }
}

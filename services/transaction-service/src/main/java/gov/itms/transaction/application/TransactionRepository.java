package gov.itms.transaction.application;

import gov.itms.transaction.domain.StatusChange;
import gov.itms.transaction.domain.Subject;
import gov.itms.transaction.domain.Transaction;
import gov.itms.transaction.domain.TransactionCursor;
import gov.itms.transaction.domain.TransactionReference;
import gov.itms.transaction.domain.TransactionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for transactions (CC-021..CC-023, CC-027). */
public interface TransactionRepository {

    /**
     * The next sequence number for (service, region, year), starting at 1 (CC-021, Q-003). Allocated inside the
     * calling database transaction, so a rolled-back attempt gives the number back.
     */
    long nextSequence(String serviceCode, String region, int year);

    /** An open transaction of the service about the subject, if any (CC-027). */
    Optional<Transaction> findOpen(String serviceCode, Subject subject);

    /**
     * Stores a new Draft; its first status change goes to the history. A concurrent duplicate open transaction is
     * refused with {@code DUPLICATE_OPEN_TRANSACTION} (CC-027).
     */
    void insert(Transaction transaction);

    Optional<Transaction> find(TransactionReference reference);

    /** Reads the transaction and locks it until the end of the database transaction. */
    Optional<Transaction> findForUpdate(TransactionReference reference);

    /**
     * Up to {@code limit} Drafts opened at or before {@code openedBy}, oldest first, locked until the end of the
     * database transaction. Drafts another database transaction holds are skipped, so several instances can run the
     * expiry job at once without waiting on each other or on a submission (CC-024).
     */
    List<Transaction> lockDraftsOpenedBy(Instant openedBy, int limit);

    /** Saves the transaction's {@link Transaction#lastChange()}; the stored version must be one less. */
    void saveStatus(Transaction transaction);

    /**
     * Up to {@code limit} transactions of the applicant, newest first by (created at, id), after {@code after}
     * ({@code null} = from the newest), optionally only in {@code status} (CC-025).
     */
    List<Transaction> findByApplicant(String applicantFileNumber, TransactionStatus status, TransactionCursor after,
                                      int limit);

    /** Every status change, oldest first (CC-023). */
    List<StatusChange> history(UUID transactionId);
}

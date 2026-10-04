package gov.itms.transaction.application;

import gov.itms.transaction.domain.StatusChange;
import gov.itms.transaction.domain.Subject;
import gov.itms.transaction.domain.Transaction;
import gov.itms.transaction.domain.TransactionReference;
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

    /** Saves the transaction's {@link Transaction#lastChange()}; the stored version must be one less. */
    void saveStatus(Transaction transaction);

    /** Every status change, oldest first (CC-023). */
    List<StatusChange> history(UUID transactionId);
}

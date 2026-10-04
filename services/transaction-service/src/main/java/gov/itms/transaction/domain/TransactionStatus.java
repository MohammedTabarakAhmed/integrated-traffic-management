package gov.itms.transaction.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Transaction lifecycle (CC-022, LLD §2.1): Draft → Submitted → Pending payment → Paid → In review → Approved or
 * Rejected → Completed, with Returned for information, Cancelled and Expired. The same transitions are enforced by
 * the database ({@code service_transaction_guard}).
 */
public enum TransactionStatus {
    DRAFT, SUBMITTED, PENDING_PAYMENT, PAID, IN_REVIEW, RETURNED_FOR_INFO, APPROVED, REJECTED, COMPLETED, CANCELLED,
    EXPIRED;

    private static final Map<TransactionStatus, Set<TransactionStatus>> NEXT = Map.ofEntries(
            Map.entry(DRAFT, EnumSet.of(SUBMITTED, EXPIRED, CANCELLED)),
            Map.entry(SUBMITTED, EnumSet.of(PENDING_PAYMENT, IN_REVIEW)),
            Map.entry(PENDING_PAYMENT, EnumSet.of(PAID, EXPIRED, CANCELLED)),
            Map.entry(PAID, EnumSet.of(IN_REVIEW)),
            Map.entry(IN_REVIEW, EnumSet.of(RETURNED_FOR_INFO, APPROVED, REJECTED)),
            Map.entry(RETURNED_FOR_INFO, EnumSet.of(IN_REVIEW, EXPIRED)),
            Map.entry(APPROVED, EnumSet.of(COMPLETED)),
            Map.entry(REJECTED, EnumSet.noneOf(TransactionStatus.class)),
            Map.entry(COMPLETED, EnumSet.noneOf(TransactionStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(TransactionStatus.class)),
            Map.entry(EXPIRED, EnumSet.noneOf(TransactionStatus.class)));

    public boolean canMoveTo(TransactionStatus next) {
        return NEXT.get(this).contains(next);
    }

    /** Statuses the lifecycle allows next; empty for a final status. */
    public Set<TransactionStatus> next() {
        return Set.copyOf(NEXT.get(this));
    }

    /**
     * Open for duplicate prevention (CC-027, Q-047): every status from which the lifecycle can still move. Completed,
     * Rejected, Cancelled and Expired are final.
     */
    public boolean isOpen() {
        return !NEXT.get(this).isEmpty();
    }
}

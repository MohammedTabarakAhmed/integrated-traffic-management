package gov.itms.transaction.domain;

/**
 * Why a transaction's status changed (CC-023). {@link #ZERO_FEE_PAYMENT_SKIPPED} records that a service without a fee
 * went from Submitted straight to In review (Q-004).
 */
public enum StatusReason {
    CREATED, SUBMITTED, PAYMENT_REQUIRED, ZERO_FEE_PAYMENT_SKIPPED, PAYMENT_CONFIRMED, REVIEW_STARTED,
    RETURNED_FOR_INFO, INFO_PROVIDED, APPROVED, REJECTED, FULFILLED, CANCELLED, EXPIRED
}

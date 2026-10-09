-- Q-073 (decided by the owner 2026-10-09): a counter reference (till slip, cheque number, terminal reference) is
-- unique per region and method, not system-wide; gateway transaction ids stay unique across all payments (FN-004).
-- The counter methods are Payment.COUNTER_METHODS.
drop index uq_payment_txn;

create unique index uq_payment_txn on payment (gateway_txn_id)
    where gateway_txn_id is not null and method not in ('CASH', 'CARD_TERMINAL', 'CHEQUE');

create unique index uq_payment_counter_ref on payment (region, method, gateway_txn_id)
    where gateway_txn_id is not null and method in ('CASH', 'CARD_TERMINAL', 'CHEQUE');

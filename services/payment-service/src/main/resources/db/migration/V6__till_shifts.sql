-- Cashier till shifts and reconciliation by payment method (FN-020).

create table cashier_till (
    id                     uuid          primary key,
    cashier_id             varchar(100)  not null,
    region                 varchar(40)   not null,
    opening_amount_minor   bigint        not null,
    opened_at              timestamptz   not null,
    status                 varchar(10)   not null,
    closed_by              varchar(100),
    closed_at              timestamptz,
    closing_reason         varchar(500),
    constraint ck_till_opening_amount check (opening_amount_minor >= 0),
    constraint ck_till_status check (status in ('OPEN', 'CLOSED')),
    constraint ck_till_closed check ((status = 'OPEN' and closed_at is null and closed_by is null)
        or (status = 'CLOSED' and closed_at is not null and closed_by is not null))
);

create unique index uq_cashier_open_till on cashier_till (cashier_id, region) where status = 'OPEN';

create table till_payment (
    payment_id uuid primary key references payment (id),
    till_id    uuid        not null references cashier_till (id),
    receipt_id uuid        not null unique references receipt (id),
    method     varchar(24) not null,
    amount_minor bigint    not null,
    recorded_at timestamptz not null,
    constraint ck_till_payment_method check (method in ('CASH', 'CARD_TERMINAL', 'CHEQUE')),
    constraint ck_till_payment_amount check (amount_minor > 0)
);

create index ix_till_payment_shift on till_payment (till_id, method);

create table till_reconciliation (
    till_id       uuid        not null references cashier_till (id),
    method        varchar(24) not null,
    expected_minor bigint     not null,
    actual_minor  bigint      not null,
    difference_minor bigint   not null,
    primary key (till_id, method),
    constraint ck_till_reconcile_method check (method in ('CASH', 'CARD_TERMINAL', 'CHEQUE'))
);

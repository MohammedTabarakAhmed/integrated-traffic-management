-- payment-service schema, part 1: payables, baskets, payments, gateway callbacks, receipts and their gapless sequences
-- (FN-001..FN-005, FN-010, FN-011, FN-014, FN-022; ADR-054). Owned exclusively by this service (ADR-016).

create table payable (
    id                  uuid          primary key,
    item_type           varchar(12)   not null,
    item_ref            varchar(60)   not null,
    traffic_file_number varchar(60),
    region              varchar(40)   not null,
    currency            char(3)       not null,
    lines               jsonb         not null,
    total_minor         bigint        not null,
    status              varchar(10)   not null,
    created_by          varchar(100)  not null,
    created_at          timestamptz   not null,
    constraint ck_payable_type check (item_type in ('TRANSACTION', 'VIOLATION', 'GENERAL', 'DEPOSIT')),
    constraint ck_payable_status check (status in ('OPEN', 'PAID', 'CANCELLED')),
    constraint ck_payable_total check (total_minor > 0)
);

-- One live payable per business item.
create unique index uq_payable_item on payable (item_type, item_ref) where status <> 'CANCELLED';
create index ix_payable_file on payable (traffic_file_number) where traffic_file_number is not null;

create function payable_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'payables are never deleted (FN-002)';
    end if;
    if (to_jsonb(new) - 'status') is distinct from (to_jsonb(old) - 'status') then
        raise exception 'only a payable''s status changes (FN-002)';
    end if;
    if old.status = 'CANCELLED' then
        raise exception 'a cancelled payable never changes (FN-002)';
    end if;
    return new;
end
$$;

create trigger trg_payable_guard before update or delete on payable
    for each row execute function payable_guard();

create table basket (
    id          uuid          primary key,
    payable_ids uuid[]        not null,
    region      varchar(40)   not null,
    currency    char(3)       not null,
    total_minor bigint        not null,
    created_by  varchar(100)  not null,
    created_at  timestamptz   not null,
    status      varchar(6)    not null,
    constraint ck_basket_status check (status in ('OPEN', 'PAID'))
);

create table payment (
    id                 uuid          primary key,
    basket_id          uuid          not null references basket (id),
    method             varchar(20)   not null,
    amount_minor       bigint        not null,
    currency           char(3)       not null,
    channel            varchar(20)   not null,
    region             varchar(40)   not null,
    payer_id           varchar(100)  not null,
    created_at         timestamptz   not null,
    status             varchar(10)   not null,
    gateway_session_id varchar(200),
    gateway_txn_id     varchar(200),
    confirmed_at       timestamptz,
    exception_reason   varchar(200),
    last_inquiry_at    timestamptz,
    constraint ck_payment_status check (status in ('PENDING', 'CONFIRMED', 'FAILED', 'EXCEPTION')),
    constraint ck_payment_confirmed check ((status = 'CONFIRMED') = (confirmed_at is not null)
        or status = 'EXCEPTION')
);

-- FN-004: one payment per gateway transaction; at most one payment in progress per basket.
create unique index uq_payment_txn on payment (gateway_txn_id) where gateway_txn_id is not null;
create unique index uq_payment_pending on payment (basket_id) where status = 'PENDING';
create index ix_payment_pending on payment (created_at) where status = 'PENDING';

-- A decided payment never changes; payments are never deleted.
create function payment_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'payments are never deleted (FN-004)';
    end if;
    if old.status <> 'PENDING' and (to_jsonb(new) is distinct from to_jsonb(old)) then
        raise exception 'a decided payment never changes (FN-004)';
    end if;
    return new;
end
$$;

create trigger trg_payment_guard before update or delete on payment
    for each row execute function payment_guard();

-- Every callback and status inquiry, including duplicates (UAT-4: "the second callback is logged as a duplicate").
create table payment_callback_log (
    id             bigint generated always as identity primary key,
    payment_id     uuid          not null,
    gateway_txn_id varchar(200),
    status         varchar(10)   not null,
    outcome        varchar(10)   not null,
    received_at    timestamptz   not null,
    constraint ck_callback_outcome check (outcome in ('ACCEPTED', 'DUPLICATE', 'INQUIRY', 'REJECTED'))
);

create index ix_callback_payment on payment_callback_log (payment_id, id);

-- Gapless receipt numbering per region, channel and year (FN-022): the row is locked while a number is taken, in the
-- same transaction as the receipt, so a rolled-back receipt also gives its number back.
create table receipt_sequence (
    region   varchar(40)  not null,
    channel  varchar(20)  not null,
    year     integer      not null,
    next_no  bigint       not null,
    constraint pk_receipt_sequence primary key (region, channel, year)
);

create table receipt (
    id                  uuid          primary key,
    number              varchar(100)  not null unique,
    payment_id          uuid          not null unique references payment (id),
    type                varchar(10)   not null,
    region              varchar(40)   not null,
    channel             varchar(20)   not null,
    method              varchar(20)   not null,
    currency            char(3)       not null,
    total_minor         bigint        not null,
    lines               jsonb         not null,
    payer_id            varchar(100)  not null,
    issued_at           timestamptz   not null,
    business_day        date          not null,
    status              varchar(10)   not null,
    cancelled_at        timestamptz,
    cancelled_by        varchar(100),
    cancellation_reason varchar(500),
    constraint ck_receipt_type check (type in ('SERVICE', 'VIOLATION', 'GENERAL', 'DEPOSIT')),
    constraint ck_receipt_status check (status in ('ISSUED', 'CANCELLED')),
    constraint ck_receipt_cancelled check ((status = 'CANCELLED') = (cancelled_at is not null
        and cancelled_by is not null and cancellation_reason is not null))
);

create index ix_receipt_payer on receipt (payer_id, issued_at desc);
create index ix_receipt_day on receipt (business_day, region);

-- Receipts are legally significant (tamper protection): only an ISSUED receipt may be cancelled, once, nothing else.
create function receipt_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'receipts are never deleted (FN-003)';
    end if;
    if old.status <> 'ISSUED' or new.status <> 'CANCELLED'
       or (to_jsonb(new) - array['status', 'cancelled_at', 'cancelled_by', 'cancellation_reason'])
          is distinct from (to_jsonb(old) - array['status', 'cancelled_at', 'cancelled_by', 'cancellation_reason']) then
        raise exception 'a receipt only changes by being cancelled, once (FN-011)';
    end if;
    return new;
end
$$;

create trigger trg_receipt_guard before update or delete on receipt
    for each row execute function receipt_guard();

create function payment_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'this table is append-only (FN-004)';
end
$$;

create trigger trg_callback_log_guard before update or delete on payment_callback_log
    for each row execute function payment_append_only();

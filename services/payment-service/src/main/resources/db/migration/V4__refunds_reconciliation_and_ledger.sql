-- Increment 5: approved refunds, ledger posting, partner settlements and reconciliation exceptions (FN-006, FN-012,
-- FN-013, FN-040, FN-041).

create table refund_request (
    id             uuid          primary key,
    payment_id     uuid          not null references payment (id),
    receipt_number varchar(100)  not null references receipt (number),
    region         varchar(40)   not null,
    currency       char(3)       not null,
    amount_minor   bigint        not null,
    reason         varchar(500)  not null,
    status         varchar(12)   not null,
    requested_by   varchar(100)  not null,
    requested_at   timestamptz   not null,
    decided_by     varchar(100),
    decided_at     timestamptz,
    remote_ref     varchar(200),
    constraint ck_refund_amount check (amount_minor > 0),
    constraint ck_refund_status check (status in ('PENDING', 'APPROVED', 'REJECTED', 'SUBMITTED', 'FAILED')),
    constraint ck_refund_decision check ((status = 'PENDING') = (decided_at is null)),
    constraint ck_refund_maker_checker check (status = 'PENDING' or decided_by <> requested_by)
);

create index ix_refund_queue on refund_request (requested_at) where status = 'PENDING';
create index ix_refund_payment on refund_request (payment_id, status);

create function refund_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'refund requests are never deleted (FN-012)';
    end if;
    if old.status <> 'PENDING' or new.status not in ('APPROVED', 'REJECTED', 'SUBMITTED')
       or (to_jsonb(new) - array['status', 'decided_by', 'decided_at'])
          is distinct from (to_jsonb(old) - array['status', 'decided_by', 'decided_at']) then
        raise exception 'a refund decision is recorded once (FN-012)';
    end if;
    return new;
end
$$;

create trigger trg_refund_guard before update or delete on refund_request
    for each row execute function refund_guard();

create table ledger_posting (
    id           bigint generated always as identity primary key,
    receipt_id   uuid          not null references receipt (id),
    line_no      integer       not null,
    financial_item varchar(40) not null,
    beneficiary  varchar(40)   not null,
    cost_centre  varchar(40)   not null,
    region       varchar(40)   not null,
    amount_minor bigint        not null,
    currency     char(3)       not null,
    created_at   timestamptz   not null,
    posted_at    timestamptz,
    external_ref varchar(200),
    last_error   varchar(500),
    constraint uq_ledger_receipt_line unique (receipt_id, line_no)
);

create index ix_ledger_pending on ledger_posting (id) where posted_at is null;

create table reconciliation_item (
    id             uuid          primary key,
    source         varchar(40)   not null,
    external_ref   varchar(200)  not null,
    paid_at        timestamptz,
    region         varchar(40)   not null,
    currency       char(3)       not null,
    amount_minor   bigint        not null,
    payment_id     uuid          references payment (id),
    status         varchar(12)   not null,
    reason         varchar(500),
    received_at    timestamptz   not null,
    resolved_by    varchar(100),
    resolved_at    timestamptz,
    constraint uq_reconciliation_source_ref unique (source, external_ref),
    constraint ck_reconciliation_status check (status in ('MATCHED', 'EXCEPTION', 'RESOLVED'))
);

create index ix_reconciliation_queue on reconciliation_item (received_at) where status = 'EXCEPTION';

create table partner_settlement (
    id             uuid          primary key,
    partner_code   varchar(40)   not null,
    settlement_ref varchar(120)  not null,
    region         varchar(40)   not null,
    currency       char(3)       not null,
    gross_minor    bigint        not null,
    commission_minor bigint      not null,
    net_minor      bigint        not null,
    settled_on     date          not null,
    status         varchar(12)   not null,
    received_at    timestamptz   not null,
    constraint uq_partner_settlement unique (partner_code, settlement_ref),
    constraint ck_partner_settlement check (gross_minor >= 0 and commission_minor >= 0
        and net_minor = gross_minor - commission_minor and status in ('RECEIVED', 'RECONCILED', 'EXCEPTION'))
);

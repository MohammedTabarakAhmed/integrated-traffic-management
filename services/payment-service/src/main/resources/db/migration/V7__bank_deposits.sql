-- Bank deposit groups reconcile issued receipts against statement lines (FN-021).

create table bank_deposit (
    id             uuid          primary key,
    deposit_ref    varchar(120)  not null unique,
    bank           varchar(120)  not null,
    region         varchar(40)   not null,
    currency       char(3)       not null,
    total_minor    bigint        not null,
    status         varchar(12)   not null,
    created_by     varchar(100)  not null,
    created_at     timestamptz   not null,
    matched_by     varchar(100),
    matched_at     timestamptz,
    reason         varchar(500),
    constraint ck_bank_deposit_total check (total_minor > 0),
    constraint ck_bank_deposit_status check (status in ('OPEN', 'MATCHED', 'EXCEPTION')),
    constraint ck_bank_deposit_match check ((status = 'MATCHED') = (matched_at is not null))
);

create table bank_deposit_receipt (
    deposit_id uuid not null references bank_deposit (id),
    receipt_id uuid not null unique references receipt (id),
    amount_minor bigint not null,
    primary key (deposit_id, receipt_id),
    constraint ck_bank_deposit_receipt_amount check (amount_minor > 0)
);

create table bank_statement_line (
    id             uuid          primary key,
    source         varchar(40)   not null,
    external_ref   varchar(200)  not null,
    bank           varchar(120)  not null,
    region         varchar(40)   not null,
    currency       char(3)       not null,
    amount_minor   bigint        not null,
    booked_at      timestamptz,
    deposit_id     uuid references bank_deposit (id),
    status         varchar(12)   not null,
    reason         varchar(500),
    received_at    timestamptz   not null,
    resolved_by    varchar(100),
    resolved_at    timestamptz,
    constraint uq_bank_statement_source_ref unique (source, external_ref),
    constraint ck_bank_statement_amount check (amount_minor >= 0),
    constraint ck_bank_statement_status check (status in ('MATCHED', 'EXCEPTION', 'RESOLVED'))
);

create index ix_bank_statement_exceptions on bank_statement_line (received_at) where status = 'EXCEPTION';

-- Increment 9 completion (XB-001..XB-008, DL-064): partner transport endpoint and agreement scope, payment closure in
-- both states, objection decisions, accident/permit/licence data exchange, maker-checker configuration, persisted
-- settlement statements and an append-only message log.

alter table partner_state
    add column endpoint_url    varchar(500),
    add column allows_accident boolean not null default false,
    add column allows_permit   boolean not null default false,
    add column allows_licence  boolean not null default false,
    add constraint ck_partner_endpoint check (endpoint_url is null or endpoint_url ~ '^https?://');

alter table exchange_message drop constraint exchange_message_message_type_check;
alter table exchange_message add constraint ck_exchange_message_type check (message_type in
    ('VIOLATION', 'PAYMENT', 'OBJECTION', 'OBJECTION_DECISION', 'ACCIDENT', 'PERMIT', 'LICENCE'));
alter table exchange_message drop constraint exchange_message_status_check;
alter table exchange_message add constraint ck_exchange_message_status check (status in
    ('PENDING', 'RECEIVED', 'LOADED', 'SENT', 'PAID', 'OBJECTED', 'CANCELLED', 'REJECTED'));

alter table exchange_message
    add column related_message_id      uuid references exchange_message (id),
    add column payload                 jsonb,
    add column paid_at                 timestamptz,
    add column paid_in                 varchar(8),
    add column payment_reference       varchar(120),
    add column commission_basis_points integer,
    add constraint ck_exchange_message_paid_in check (paid_in is null or paid_in in ('HERE', 'PARTNER')),
    add constraint ck_exchange_message_paid check ((paid_at is null) = (paid_in is null)
        and (paid_at is null) = (commission_basis_points is null)),
    add constraint ck_exchange_message_commission check (commission_basis_points is null
        or commission_basis_points between 0 and 10000);

create index ix_exchange_message_local_reference on exchange_message (local_reference)
    where local_reference is not null;
create index ix_exchange_message_paid on exchange_message (partner_code, paid_at) where paid_at is not null;
create index ix_exchange_message_list on exchange_message (partner_code, received_at desc);

-- XB-007: every message and every status change is kept; facts never change and nothing is deleted.
create table exchange_message_history (
    id          bigint        generated always as identity primary key,
    message_id  uuid          not null references exchange_message (id),
    from_status varchar(16),
    to_status   varchar(16)   not null,
    detail      varchar(1000),
    changed_by  varchar(120)  not null,
    changed_at  timestamptz   not null
);
create index ix_exchange_message_history on exchange_message_history (message_id, id);

insert into exchange_message_history (message_id, from_status, to_status, detail, changed_by, changed_at)
select id, null, status, detail, 'SYSTEM_MIGRATION', coalesce(updated_at, received_at) from exchange_message;

create function exchange_message_guard() returns trigger language plpgsql as $$
declare
    mutable text[] := array['status', 'detail', 'updated_at', 'local_reference', 'paid_at', 'paid_in',
                            'payment_reference', 'commission_basis_points'];
begin
    if tg_op = 'DELETE' then
        raise exception 'exchange messages are never deleted (XB-007)';
    end if;
    if (to_jsonb(new) - mutable) is distinct from (to_jsonb(old) - mutable) then
        raise exception 'an exchange message''s content never changes (XB-007)';
    end if;
    if old.local_reference is not null and new.local_reference is distinct from old.local_reference then
        raise exception 'a message''s local reference is set once (XB-007)';
    end if;
    if old.paid_at is not null and (new.paid_at is distinct from old.paid_at
            or new.paid_in is distinct from old.paid_in) then
        raise exception 'a recorded payment never changes (XB-003)';
    end if;
    return new;
end
$$;
create trigger trg_exchange_message_guard before update or delete on exchange_message
    for each row execute function exchange_message_guard();

create function crossborder_append_only() returns trigger language plpgsql as $$
begin
    raise exception '% rows are never changed or deleted (XB-004, XB-007)', tg_table_name;
end
$$;
create trigger trg_exchange_message_history_guard before update or delete on exchange_message_history
    for each row execute function crossborder_append_only();

-- Partner settings and code mappings change only through maker-checker proposals (XB-007, XB-008).
create table config_proposal (
    id              uuid          primary key,
    kind            varchar(16)   not null,
    subject         varchar(200)  not null,
    payload         jsonb         not null,
    status          varchar(10)   not null,
    proposed_by     varchar(120)  not null,
    proposed_at     timestamptz   not null,
    reason          varchar(500)  not null,
    decided_by      varchar(120),
    decided_at      timestamptz,
    decision_reason varchar(500),
    constraint ck_config_proposal_kind check (kind in ('PARTNER', 'MAPPING')),
    constraint ck_config_proposal_status check (status in ('PROPOSED', 'APPROVED', 'REJECTED')),
    constraint ck_config_proposal_decided check ((status = 'PROPOSED') = (decided_at is null)
        and (decided_at is null) = (decided_by is null)),
    constraint ck_config_proposal_four_eyes check (decided_by is null or decided_by <> proposed_by)
);
create unique index uq_config_proposal_open on config_proposal (kind, subject) where status = 'PROPOSED';

-- XB-004: periodic statements of what each state owes the other; a paid violation is settled once.
create table settlement_statement (
    id                         uuid          primary key,
    partner_code               varchar(12)   not null references partner_state (code),
    period_from                timestamptz   not null,
    period_to                  timestamptz   not null,
    currency                   char(3)       not null,
    collected_here_minor       bigint        not null,
    commission_here_minor      bigint        not null,
    owed_to_partner_minor      bigint        not null,
    collected_by_partner_minor bigint        not null,
    commission_partner_minor   bigint        not null,
    owed_by_partner_minor      bigint        not null,
    net_minor                  bigint        not null,
    line_count                 integer       not null,
    generated_by               varchar(120)  not null,
    generated_at               timestamptz   not null,
    constraint ck_settlement_period check (period_from < period_to),
    constraint ck_settlement_here check (owed_to_partner_minor = collected_here_minor - commission_here_minor),
    constraint ck_settlement_partner check (owed_by_partner_minor = collected_by_partner_minor
        - commission_partner_minor),
    constraint ck_settlement_net check (net_minor = owed_by_partner_minor - owed_to_partner_minor)
);
create index ix_settlement_statement_partner on settlement_statement (partner_code, period_from);

create table settlement_line (
    statement_id            uuid          not null references settlement_statement (id),
    message_id              uuid          not null references exchange_message (id),
    paid_in                 varchar(8)    not null,
    amount_minor            bigint        not null,
    commission_basis_points integer       not null,
    commission_minor        bigint        not null,
    primary key (statement_id, message_id),
    constraint uq_settlement_line_message unique (message_id),
    constraint ck_settlement_line_paid_in check (paid_in in ('HERE', 'PARTNER'))
);

create trigger trg_settlement_statement_guard before update or delete on settlement_statement
    for each row execute function crossborder_append_only();
create trigger trg_settlement_line_guard before update or delete on settlement_line
    for each row execute function crossborder_append_only();

-- Delivery acknowledgements keep the attempt's own retry time; FAILED after the configured attempts.
alter table exchange_delivery add column partner_reference varchar(120);

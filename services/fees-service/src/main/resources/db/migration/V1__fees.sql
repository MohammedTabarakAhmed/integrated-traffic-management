-- fees-service schema, part 1: fee versions and quotes (CC-040..CC-042, CC-046, CC-047; ADR-046). Owned exclusively by
-- this service (ADR-016). Amounts are integer minor units of the configured currency (Q-006).

create table fee_version (
    id                  uuid           primary key,
    fee_code            varchar(40)    not null,
    version_no          integer        not null,
    service_code        varchar(20)    not null,
    name_nl             varchar(200)   not null,
    name_en             varchar(200)   not null,
    region              varchar(40),
    customer_category   varchar(40),
    subject_category    varchar(40),
    financial_item      varchar(40)    not null,
    beneficiary         varchar(40)    not null,
    method              jsonb          not null,
    currency            char(3)        not null,
    effective_from      date           not null,
    effective_to        date,
    status              varchar(10)    not null,
    submitted_at        timestamptz    not null,
    submitted_by        varchar(100)   not null,
    submitted_by_username varchar(200),
    submitted_channel   varchar(20)    not null,
    submit_reason       varchar(500)   not null,
    decided_at          timestamptz,
    decided_by          varchar(100),
    decided_by_username varchar(200),
    decided_channel     varchar(20),
    decision_reason     varchar(500),
    constraint uq_fee_version unique (fee_code, version_no),
    constraint ck_fee_codes check (fee_code ~ '^[A-Z0-9_]{1,40}$' and service_code ~ '^[A-Z][A-Z0-9_]{1,19}$'
        and financial_item ~ '^[A-Z0-9_]{1,40}$' and beneficiary ~ '^[A-Z0-9_]{1,40}$'
        and (region is null or region ~ '^[A-Z0-9_]{1,40}$')
        and (customer_category is null or customer_category ~ '^[A-Z0-9_]{1,40}$')
        and (subject_category is null or subject_category ~ '^[A-Z0-9_]{1,40}$')
        and currency ~ '^[A-Z]{3}$'),
    constraint ck_fee_method check (method ->> 'type' in ('FIXED', 'PER_UNIT', 'TIERED', 'FORMULA')),
    constraint ck_fee_dates check (effective_to is null or effective_to >= effective_from),
    constraint ck_fee_status check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    constraint ck_fee_decided check ((status = 'PENDING') = (decided_at is null)),
    constraint ck_fee_maker_checker check (status <> 'APPROVED' or decided_by <> submitted_by)
);

create index ix_fee_version_service on fee_version (service_code, status, effective_from);

-- A version is decided once (PENDING -> APPROVED/REJECTED); an approved version may later only have its end date
-- brought forward when a successor is approved. Nothing else changes and nothing is deleted (CC-046, CC-122).
create function fee_version_guard() returns trigger language plpgsql as $$
declare
    fixed text[] := array['status', 'decided_at', 'decided_by', 'decided_by_username', 'decided_channel',
                          'decision_reason', 'effective_to'];
begin
    if tg_op = 'DELETE' then
        raise exception 'fee versions are never deleted (CC-046)';
    end if;
    if (to_jsonb(new) - fixed) is distinct from (to_jsonb(old) - fixed) then
        raise exception 'a fee version never changes after it is submitted (CC-046)';
    end if;
    if old.status = 'PENDING' then
        if new.status = 'PENDING' or new.effective_to is distinct from old.effective_to then
            raise exception 'a pending fee version is only approved or rejected (CC-046)';
        end if;
    elsif old.status = 'APPROVED' then
        if new.status <> 'APPROVED'
           or (to_jsonb(new) - 'effective_to') is distinct from (to_jsonb(old) - 'effective_to')
           or new.effective_to is null
           or (old.effective_to is not null and new.effective_to >= old.effective_to) then
            raise exception 'an approved fee version may only end earlier (CC-046)';
        end if;
    else
        raise exception 'a rejected fee version never changes (CC-046)';
    end if;
    return new;
end
$$;

create trigger trg_fee_version_guard before update or delete on fee_version
    for each row execute function fee_version_guard();

-- Quotes (CC-047): frozen itemised breakdowns. Written once, never changed (CC-046).
create table fee_quote (
    id                uuid          primary key,
    service_code      varchar(20)   not null,
    region            varchar(40)   not null,
    customer_category varchar(40)   not null,
    subject_category  varchar(40),
    quantities        jsonb         not null,
    quote_date        date          not null,
    lines             jsonb         not null,
    total             bigint        not null,
    currency          char(3)       not null,
    created_at        timestamptz   not null,
    expires_at        timestamptz   not null,
    created_by        varchar(100)  not null,
    constraint ck_quote_total check (total >= 0),
    constraint ck_quote_expiry check (expires_at > created_at)
);

create index ix_fee_quote_expiry on fee_quote (expires_at);

create function fee_quote_guard() returns trigger language plpgsql as $$
begin
    raise exception 'quotes are frozen (CC-046)';
end
$$;

create trigger trg_fee_quote_guard before update on fee_quote
    for each row execute function fee_quote_guard();

-- fees-service schema, part 3: exemptions and discounts (CC-043), late fees (CC-044) and levies (CC-045); ADR-047.

alter table fee_version drop constraint ck_fee_method;
alter table fee_version add constraint ck_fee_method
    check (method ->> 'type' in ('FIXED', 'PER_UNIT', 'TIERED', 'FORMULA', 'LATE'));

-- Quotes remember the campaign and expiry date they were made with.
alter table fee_quote
    add column campaign_code varchar(40),
    add column expiry_date   date;

create table fee_adjustment (
    id                  uuid           primary key,
    code                varchar(40)    not null,
    kind                varchar(10)    not null,
    service_code        varchar(20)    not null,
    name_nl             varchar(200)   not null,
    name_en             varchar(200)   not null,
    fee_codes           varchar(40)[]  not null default '{}',
    customer_category   varchar(40),
    campaign_code       varchar(40),
    percent             numeric(5, 2),
    amount              bigint,
    financial_item      varchar(40),
    beneficiary         varchar(40),
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
    constraint ck_adj_kind check (kind in ('EXEMPTION', 'DISCOUNT', 'LEVY')),
    constraint ck_adj_codes check (code ~ '^[A-Z0-9_]{1,40}$' and service_code ~ '^[A-Z][A-Z0-9_]{1,19}$'),
    constraint ck_adj_values check (
        (kind = 'EXEMPTION' and percent is null and amount is null
            and (customer_category is not null or campaign_code is not null))
        or (kind = 'DISCOUNT' and (percent is null) <> (amount is null)
            and (customer_category is not null or campaign_code is not null))
        or (kind = 'LEVY' and percent is not null and amount is null and financial_item is not null
            and beneficiary is not null)),
    constraint ck_adj_percent check (percent is null or (percent > 0 and percent <= 100)),
    constraint ck_adj_amount check (amount is null or amount >= 0),
    constraint ck_adj_dates check (effective_to is null or effective_to >= effective_from),
    constraint ck_adj_status check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    constraint ck_adj_decided check ((status = 'PENDING') = (decided_at is null)),
    constraint ck_adj_maker_checker check (status <> 'APPROVED' or decided_by <> submitted_by)
);

create index ix_fee_adjustment_service on fee_adjustment (service_code, status);

-- Decided once; never changed otherwise or deleted (CC-046).
create function fee_adjustment_guard() returns trigger language plpgsql as $$
declare
    decision text[] := array['status', 'decided_at', 'decided_by', 'decided_by_username', 'decided_channel',
                             'decision_reason'];
begin
    if tg_op = 'DELETE' then
        raise exception 'fee adjustments are never deleted (CC-046)';
    end if;
    if old.status <> 'PENDING' or new.status = 'PENDING'
       or (to_jsonb(new) - decision) is distinct from (to_jsonb(old) - decision) then
        raise exception 'a fee adjustment is only decided once (CC-046)';
    end if;
    return new;
end
$$;

create trigger trg_fee_adjustment_guard before update or delete on fee_adjustment
    for each row execute function fee_adjustment_guard();

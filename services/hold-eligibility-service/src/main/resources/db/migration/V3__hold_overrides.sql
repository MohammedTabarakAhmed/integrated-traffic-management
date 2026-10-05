-- hold-eligibility-service schema, part 3: supervisor overrides of a hold for one transaction (CC-034; ADR-035).
-- The hold itself stays Active and unchanged (LLD §2.2); an override only stops it blocking the one transaction named.

create table hold_override (
    id                     uuid           primary key,
    hold_id                uuid           not null references hold (id),
    -- transaction-service's reference (CC-021); the service code is its first part.
    transaction_reference  varchar(80)    not null,
    service_code           varchar(20)    not null,
    reason                 varchar(500)   not null,
    -- The supporting document: a reference until the document service exists (CC-080, Q-055).
    document_type          varchar(40)    not null,
    document_number        varchar(100)   not null,
    overridden_at          timestamptz    not null,
    overridden_by          varchar(100)   not null,
    overridden_by_username varchar(200),
    overridden_channel     varchar(20)    not null,
    constraint uq_hold_override_transaction unique (hold_id, transaction_reference),
    constraint ck_hold_override_reference check (
        transaction_reference ~ '^[A-Z][A-Z0-9_]{1,19}-[A-Z0-9_]{1,40}-[0-9]{4}-[0-9]{7}$'
        and split_part(transaction_reference, '-', 1) = service_code),
    constraint ck_hold_override_reason check (char_length(btrim(reason)) between 3 and 500),
    constraint ck_hold_override_document check (document_type ~ '^[A-Z0-9_]{1,40}$'
        and document_number ~ '^[A-Za-z0-9._:/-]{1,100}$'),
    constraint ck_hold_override_channel check (overridden_channel in ('PORTAL', 'APP', 'BACKOFFICE', 'PARTNER',
        'KIOSK', 'CALLCENTRE', 'FIELD', 'ASSISTANT', 'AGENT'))
);

create index ix_hold_override_transaction on hold_override (transaction_reference);

-- An override is recorded once and never changed or deleted (CC-034, CC-122); the row is its own history.
create function hold_override_guard() returns trigger language plpgsql as $$
declare
    placer varchar(100);
begin
    if tg_op <> 'INSERT' then
        raise exception 'hold overrides are never changed or deleted (CC-034)';
    end if;
    -- Segregation of duties (CC-002, Q-055): the officer who placed the hold cannot override it.
    select placed_by into placer from hold where id = new.hold_id;
    if placer = new.overridden_by then
        raise exception 'the officer who placed a hold cannot override it (CC-034)';
    end if;
    return new;
end
$$;

create trigger trg_hold_override_guard before insert or update or delete on hold_override
    for each row execute function hold_override_guard();

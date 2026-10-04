-- reference-data-service schema (CC-110, CC-112, CC-114, DAT-002). Owned exclusively by this service (ADR-016).

create table reference_list (
    code             varchar(40)  primary key,
    name_nl          varchar(200) not null,
    name_en          varchar(200) not null,
    parent_list_code varchar(40)  references reference_list (code),
    created_at       timestamptz  not null,
    created_by       varchar(100) not null,
    created_by_change uuid,
    constraint ck_list_code check (code ~ '^[A-Z0-9_]{1,40}$')
);

create table reference_item (
    id             uuid         primary key,
    list_code      varchar(40)  not null references reference_list (code),
    code           varchar(40)  not null,
    region_code    varchar(40),
    name_nl        varchar(200) not null,
    name_en        varchar(200) not null,
    active         boolean      not null,
    effective_from date         not null,
    effective_to   date,
    parent_code    varchar(40),
    external_codes jsonb        not null default '{}'::jsonb,
    sort_order     integer      not null default 0,
    version        bigint       not null,
    last_change_id uuid,
    created_at     timestamptz  not null,
    created_by     varchar(100) not null,
    updated_at     timestamptz  not null,
    updated_by     varchar(100) not null,
    constraint uq_reference_item unique nulls not distinct (list_code, code, region_code),
    constraint ck_item_code check (code ~ '^[A-Z0-9_]{1,40}$'),
    constraint ck_item_region check (region_code is null or region_code ~ '^[A-Z0-9_]{1,40}$'),
    constraint ck_item_dates check (effective_to is null or effective_to >= effective_from),
    constraint ck_item_external_codes check (jsonb_typeof(external_codes) = 'object')
);

create index ix_reference_item_list on reference_item (list_code, region_code);

-- Temporal history (DAT-002): every version of every item, with the period during which it was current.
-- Written by trigger in the same transaction as the change, so it cannot be skipped by application code.
create table reference_item_history (
    history_id     bigserial    primary key,
    item_id        uuid         not null,
    version        bigint       not null,
    list_code      varchar(40)  not null,
    code           varchar(40)  not null,
    region_code    varchar(40),
    name_nl        varchar(200) not null,
    name_en        varchar(200) not null,
    active         boolean      not null,
    effective_from date         not null,
    effective_to   date,
    parent_code    varchar(40),
    external_codes jsonb        not null,
    sort_order     integer      not null,
    valid_from     timestamptz  not null,
    valid_to       timestamptz,
    changed_by     varchar(100) not null,
    change_id      uuid,
    constraint uq_item_history_version unique (item_id, version)
);

create index ix_item_history_lookup on reference_item_history (list_code, code, region_code, valid_from);

create function reference_item_history_capture() returns trigger language plpgsql as $$
begin
    update reference_item_history
       set valid_to = new.updated_at
     where item_id = new.id and valid_to is null;
    insert into reference_item_history (item_id, version, list_code, code, region_code, name_nl, name_en, active,
                                        effective_from, effective_to, parent_code, external_codes, sort_order,
                                        valid_from, changed_by, change_id)
    values (new.id, new.version, new.list_code, new.code, new.region_code, new.name_nl, new.name_en, new.active,
            new.effective_from, new.effective_to, new.parent_code, new.external_codes, new.sort_order,
            new.updated_at, new.updated_by, new.last_change_id);
    return new;
end
$$;

create trigger trg_reference_item_history
    after insert or update on reference_item
    for each row execute function reference_item_history_capture();

-- Items are never deleted (deactivate or end-date instead); history rows are never rewritten except closing valid_to.
create function reference_data_no_delete() returns trigger language plpgsql as $$
begin
    raise exception '% rows cannot be deleted; deactivate or end-date instead (DAT-002)', tg_table_name;
end
$$;

create trigger trg_reference_item_no_delete before delete on reference_item
    for each row execute function reference_data_no_delete();
create trigger trg_reference_item_history_no_delete before delete on reference_item_history
    for each row execute function reference_data_no_delete();
create trigger trg_reference_list_no_delete before delete on reference_list
    for each row execute function reference_data_no_delete();

-- Maker-checker change requests (CC-114).
create table reference_change (
    id               uuid         primary key,
    change_type      varchar(20)  not null,
    status           varchar(20)  not null,
    list_code        varchar(40)  not null,
    item_code        varchar(40),
    region_code      varchar(40),
    name_nl          varchar(200) not null,
    name_en          varchar(200) not null,
    parent_list_code varchar(40),
    active           boolean,
    effective_from   date,
    effective_to     date,
    parent_code      varchar(40),
    external_codes   jsonb,
    sort_order       integer,
    base_version     bigint,
    reason           varchar(500) not null,
    maker_id         varchar(100) not null,
    maker_username   varchar(200),
    submitted_at     timestamptz  not null,
    checker_id       varchar(100),
    checker_username varchar(200),
    decided_at       timestamptz,
    decision_reason  varchar(500),
    constraint ck_change_type check (change_type in ('CREATE_LIST', 'CREATE_ITEM', 'UPDATE_ITEM')),
    constraint ck_change_status check (status in ('PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    -- Defence in depth for segregation of duties (CC-002): enforced in the domain and again here.
    constraint ck_change_sod check (checker_id is null or checker_id <> maker_id),
    constraint ck_change_decided check ((status = 'PENDING_APPROVAL') = (checker_id is null))
);

-- At most one pending change per target entry.
create unique index uq_reference_change_pending_target
    on reference_change (list_code, coalesce(item_code, '#LIST'), coalesce(region_code, '#NATIONAL'))
    where status = 'PENDING_APPROVAL';

create index ix_reference_change_status on reference_change (status, submitted_at desc);

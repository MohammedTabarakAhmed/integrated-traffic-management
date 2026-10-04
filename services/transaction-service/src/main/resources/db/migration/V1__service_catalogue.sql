-- transaction-service schema, part 1: the service catalogue (CC-020, CC-026, CC-112, CC-113, CC-114, DAT-002).
-- Owned exclusively by this service (ADR-016). Transactions (CC-021..CC-027) come in a later migration.

-- txn_strip mirrors Java String.strip() (Character.isWhitespace), which the domain uses, so the text checks below
-- accept exactly what the domain accepts (same rule as iam-service's iam_strip, ADR-026).
create function txn_strip(value text) returns text language sql immutable strict parallel safe as $$
    select regexp_replace(value, '^[\u0009-\u000d\u001c-   -  -    　]+|[\u0009-\u000d\u001c-   -  -    　]+$', '', 'g')
$$;

create function txn_codes_valid(codes text[], pattern text) returns boolean language sql immutable strict
    parallel safe as $$
    select coalesce(bool_and(c ~ pattern), true) from unnest(codes) as c
$$;

create table service_definition (
    code                   varchar(20)   primary key,
    name_nl                varchar(200)  not null,
    name_en                varchar(200)  not null,
    module                 varchar(40)   not null,
    customer_types         text[]        not null,
    channels               text[]        not null,
    required_documents     text[]        not null,
    fee_code               varchar(40),
    rule_set_code          varchar(40),
    workflow_code          varchar(40),
    sla                    varchar(20)   not null,
    regions                text[]        not null,
    any_region             boolean       not null,
    allows_concurrent_open boolean       not null,
    version                bigint        not null,
    last_change_id         uuid          not null,
    created_at             timestamptz   not null,
    created_by             varchar(100)  not null,
    updated_at             timestamptz   not null,
    updated_by             varchar(100)  not null,
    constraint ck_service_code check (code ~ '^[A-Z][A-Z0-9_]{1,19}$'),
    constraint ck_service_names check (char_length(txn_strip(name_nl)) >= 1 and char_length(txn_strip(name_en)) >= 1),
    constraint ck_service_module check (module ~ '^[A-Z0-9_]{1,40}$'),
    constraint ck_service_customer_types check (cardinality(customer_types) between 1 and 50
        and txn_codes_valid(customer_types, '^[A-Z0-9_]{1,40}$')),
    constraint ck_service_channels check (cardinality(channels) between 1 and 9
        and channels <@ array['PORTAL', 'APP', 'BACKOFFICE', 'PARTNER', 'KIOSK', 'CALLCENTRE', 'FIELD', 'ASSISTANT',
                              'AGENT']),
    constraint ck_service_documents check (cardinality(required_documents) <= 50
        and txn_codes_valid(required_documents, '^[A-Z0-9_]{1,40}$')),
    constraint ck_service_refs check ((fee_code is null or fee_code ~ '^[A-Z0-9_]{1,40}$')
        and (rule_set_code is null or rule_set_code ~ '^[A-Z0-9_]{1,40}$')
        and (workflow_code is null or workflow_code ~ '^[A-Z0-9_]{1,40}$')),
    constraint ck_service_sla check (sla ~ '^P') ,
    -- Either every region ('*' alone) or a list of region codes.
    constraint ck_service_regions check (cardinality(regions) between 1 and 100
        and (regions = array['*'] or txn_codes_valid(regions, '^[A-Z0-9_]{1,40}$'))),
    constraint ck_service_version check (version >= 1)
);

-- Temporal history (DAT-002): every version of every definition and the period it was current. Written by trigger in
-- the same transaction, so application code cannot skip it.
create table service_definition_history (
    history_id             bigserial     primary key,
    code                   varchar(20)   not null,
    version                bigint        not null,
    name_nl                varchar(200)  not null,
    name_en                varchar(200)  not null,
    module                 varchar(40)   not null,
    customer_types         text[]        not null,
    channels               text[]        not null,
    required_documents     text[]        not null,
    fee_code               varchar(40),
    rule_set_code          varchar(40),
    workflow_code          varchar(40),
    sla                    varchar(20)   not null,
    regions                text[]        not null,
    any_region             boolean       not null,
    allows_concurrent_open boolean       not null,
    valid_from             timestamptz   not null,
    valid_to               timestamptz,
    changed_by             varchar(100)  not null,
    change_id              uuid          not null,
    constraint uq_service_history_version unique (code, version)
);

create function service_definition_history_capture() returns trigger language plpgsql as $$
begin
    if tg_op = 'UPDATE' then
        if new.code <> old.code then
            raise exception 'a service code cannot change (DAT-002)';
        end if;
        if new.version <> old.version + 1 then
            raise exception 'a service definition version must increase by one (DAT-002)';
        end if;
    end if;
    update service_definition_history
       set valid_to = new.updated_at
     where code = new.code and valid_to is null;
    insert into service_definition_history (code, version, name_nl, name_en, module, customer_types, channels,
                                            required_documents, fee_code, rule_set_code, workflow_code, sla, regions,
                                            any_region, allows_concurrent_open, valid_from, changed_by, change_id)
    values (new.code, new.version, new.name_nl, new.name_en, new.module, new.customer_types, new.channels,
            new.required_documents, new.fee_code, new.rule_set_code, new.workflow_code, new.sla, new.regions,
            new.any_region, new.allows_concurrent_open, new.updated_at, new.updated_by, new.last_change_id);
    return new;
end
$$;

create trigger trg_service_definition_history
    after insert or update on service_definition
    for each row execute function service_definition_history_capture();

-- History rows are written once; only valid_to may be set, once.
create function service_definition_history_guard() returns trigger language plpgsql as $$
begin
    if old.valid_to is not null
       or (to_jsonb(new) - 'valid_to') is distinct from (to_jsonb(old) - 'valid_to') then
        raise exception 'service definition history is append-only (DAT-002)';
    end if;
    return new;
end
$$;

create trigger trg_service_definition_history_guard before update on service_definition_history
    for each row execute function service_definition_history_guard();

-- Availability rules (CC-026): enable or disable a service for a region and channel over a date range.
create table availability_rule (
    id             uuid          primary key,
    service_code   varchar(20)   not null references service_definition (code),
    region         varchar(40)   not null,
    channel        varchar(20)   not null,
    enabled        boolean       not null,
    effective_from date          not null,
    effective_to   date,
    change_id      uuid          not null,
    created_at     timestamptz   not null,
    created_by     varchar(100)  not null,
    ended_by_change uuid,
    constraint ck_rule_region check (region = '*' or region ~ '^[A-Z0-9_]{1,40}$'),
    constraint ck_rule_channel check (channel in ('*', 'PORTAL', 'APP', 'BACKOFFICE', 'PARTNER', 'KIOSK', 'CALLCENTRE',
                                                  'FIELD', 'ASSISTANT', 'AGENT')),
    constraint ck_rule_dates check (effective_to is null or effective_to >= effective_from)
);

create index ix_rule_service on availability_rule (service_code, effective_from);

-- Rules are append-only. The only change allowed is ending a rule: setting (or bringing forward) effective_to, with
-- the change that ended it.
create function availability_rule_guard() returns trigger language plpgsql as $$
begin
    if (to_jsonb(new) - array['effective_to', 'ended_by_change'])
       is distinct from (to_jsonb(old) - array['effective_to', 'ended_by_change']) then
        raise exception 'availability rules are append-only; only the end date can change (CC-026, DAT-002)';
    end if;
    if new.effective_to is null
       or (old.effective_to is not null and new.effective_to > old.effective_to)
       or new.ended_by_change is null then
        raise exception 'an availability rule can only be ended earlier, by a change (CC-026)';
    end if;
    return new;
end
$$;

create trigger trg_availability_rule_guard before update on availability_rule
    for each row execute function availability_rule_guard();

-- Two rules for the same service, region and channel may not be in force on the same day (no ambiguity, ADR-028).
create function availability_rule_overlap_check() returns trigger language plpgsql as $$
begin
    if exists (select 1 from availability_rule r
                where r.service_code = new.service_code and r.region = new.region and r.channel = new.channel
                  and r.id <> new.id
                  and daterange(r.effective_from, r.effective_to, '[]')
                      && daterange(new.effective_from, new.effective_to, '[]')) then
        raise exception 'availability rules for the same region and channel may not overlap (CC-026)';
    end if;
    return new;
end
$$;

create trigger trg_availability_rule_overlap before insert or update on availability_rule
    for each row execute function availability_rule_overlap_check();

-- Maker-checker changes (CC-114, CC-002). The proposed content is kept as submitted (payload), for audit.
create table catalogue_change (
    id               uuid          primary key,
    change_type      varchar(30)   not null,
    status           varchar(20)   not null,
    service_code     varchar(20)   not null,
    payload          jsonb         not null,
    base_version     bigint,
    rule_id          uuid,
    end_date         date,
    reason           varchar(500)  not null,
    maker_id         varchar(100)  not null,
    maker_username   varchar(200),
    submitted_at     timestamptz   not null,
    checker_id       varchar(100),
    checker_username varchar(200),
    decided_at       timestamptz,
    decision_reason  varchar(500),
    constraint ck_change_type check (change_type in ('CREATE_SERVICE', 'UPDATE_SERVICE', 'ADD_AVAILABILITY_RULE',
                                                     'END_AVAILABILITY_RULE')),
    constraint ck_change_status check (status in ('PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    constraint ck_change_service_code check (service_code ~ '^[A-Z][A-Z0-9_]{1,19}$'),
    constraint ck_change_payload check (jsonb_typeof(payload) = 'object'),
    constraint ck_change_reason check (char_length(txn_strip(reason)) >= 3),
    constraint ck_change_decision_reason check (decision_reason is null or char_length(txn_strip(decision_reason)) >= 3),
    constraint ck_change_shape check (
        (change_type <> 'UPDATE_SERVICE' or base_version >= 1)
        and (change_type <> 'END_AVAILABILITY_RULE' or (rule_id is not null and end_date is not null))),
    -- Segregation of duties (CC-002): enforced in the domain and again here.
    constraint ck_change_sod check (checker_id is null or checker_id <> maker_id),
    constraint ck_change_decided check ((status = 'PENDING_APPROVAL') = (checker_id is null and decided_at is null)),
    constraint ck_change_rejection_reason check (status <> 'REJECTED' or decision_reason is not null)
);

-- At most one pending change per service: changes to one service are decided one at a time.
create unique index uq_catalogue_change_pending on catalogue_change (service_code) where status = 'PENDING_APPROVAL';
create index ix_catalogue_change_status on catalogue_change (status, submitted_at desc);

-- A change is submitted once and decided once; the proposal itself never changes.
create function catalogue_change_guard() returns trigger language plpgsql as $$
begin
    if old.status <> 'PENDING_APPROVAL'
       or (to_jsonb(new) - array['status', 'checker_id', 'checker_username', 'decided_at', 'decision_reason'])
          is distinct from
          (to_jsonb(old) - array['status', 'checker_id', 'checker_username', 'decided_at', 'decision_reason']) then
        raise exception 'a catalogue change is decided once and never edited (CC-114)';
    end if;
    return new;
end
$$;

create trigger trg_catalogue_change_guard before update on catalogue_change
    for each row execute function catalogue_change_guard();

-- Nothing in the catalogue is ever deleted: end-date a rule or update a definition instead.
create function catalogue_no_delete() returns trigger language plpgsql as $$
begin
    raise exception '% rows cannot be deleted (DAT-002)', tg_table_name;
end
$$;

create trigger trg_service_definition_no_delete before delete on service_definition
    for each row execute function catalogue_no_delete();
create trigger trg_service_definition_history_no_delete before delete on service_definition_history
    for each row execute function catalogue_no_delete();
create trigger trg_availability_rule_no_delete before delete on availability_rule
    for each row execute function catalogue_no_delete();
create trigger trg_catalogue_change_no_delete before delete on catalogue_change
    for each row execute function catalogue_no_delete();

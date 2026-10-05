-- hold-eligibility-service schema, part 1: holds (CC-030, CC-031; ADR-033). Owned exclusively by this service
-- (ADR-016). Overrides (CC-034), external holds (CC-033) and eligibility checks (CC-032) come in later slices.

-- CC-030: a hold on a traffic file, licence, vehicle or plate. Never deleted: it is lifted once, with a reason, or it
-- expires by its end date (the end date is the last day it applies; EXPIRED is derived, not stored).
create table hold (
    id                     uuid           primary key,
    subject_type           varchar(20)    not null,
    subject_id             varchar(40)    not null,
    hold_type              varchar(40)    not null,
    -- The module that owns the hold; data scope of every action on it (CC-001, Q-053).
    module                 varchar(40)    not null,
    source                 varchar(40)    not null,
    source_ref             varchar(100),
    reason                 varchar(500)   not null,
    start_date             date           not null,
    end_date               date,
    -- Service codes the hold blocks, or {'*'} for every service (CC-030).
    blocked_services       varchar(20)[]  not null,
    -- Roles whose holders (with hold.lift in the hold's module) may lift it (CC-030).
    liftable_by_roles      varchar(60)[]  not null,
    status                 varchar(10)    not null,
    placed_at              timestamptz    not null,
    placed_by              varchar(100)   not null,
    placed_by_username     varchar(200),
    placed_channel         varchar(20)    not null,
    lifted_at              timestamptz,
    lifted_by              varchar(100),
    lifted_by_username     varchar(200),
    lifted_channel         varchar(20),
    lift_reason            varchar(500),
    version                bigint         not null,
    constraint ck_hold_subject check (subject_type in ('TRAFFIC_FILE', 'DRIVING_LICENCE', 'VEHICLE', 'PLATE')
        and subject_id ~ '^[A-Z0-9][A-Z0-9-]{0,39}$'),
    constraint ck_hold_codes check (hold_type ~ '^[A-Z0-9_]{1,40}$' and module ~ '^[A-Z0-9_]{1,40}$'
        and source ~ '^[A-Z0-9_]{1,40}$'
        and (source_ref is null or source_ref ~ '^[A-Za-z0-9._:/-]{1,100}$')),
    constraint ck_hold_reason check (char_length(btrim(reason)) between 3 and 500),
    constraint ck_hold_dates check (end_date is null or end_date >= start_date),
    constraint ck_hold_blocked_services check (cardinality(blocked_services) between 1 and 100
        and (blocked_services = array['*']::varchar(20)[]
             or array_to_string(blocked_services, ',') ~ '^[A-Z][A-Z0-9_]{1,19}(,[A-Z][A-Z0-9_]{1,19})*$')),
    constraint ck_hold_liftable_roles check (cardinality(liftable_by_roles) between 1 and 20
        and array_to_string(liftable_by_roles, ',') ~ '^[A-Z][A-Z0-9_]{1,59}(,[A-Z][A-Z0-9_]{1,59})*$'),
    constraint ck_hold_status check (status in ('ACTIVE', 'LIFTED')),
    constraint ck_hold_channels check (
        placed_channel in ('PORTAL', 'APP', 'BACKOFFICE', 'PARTNER', 'KIOSK', 'CALLCENTRE', 'FIELD', 'ASSISTANT', 'AGENT')
        and (lifted_channel is null or lifted_channel in ('PORTAL', 'APP', 'BACKOFFICE', 'PARTNER', 'KIOSK',
                                                           'CALLCENTRE', 'FIELD', 'ASSISTANT', 'AGENT'))),
    constraint ck_hold_lifted check ((status = 'LIFTED') = (lifted_at is not null and lifted_by is not null
        and lifted_channel is not null and lift_reason is not null and char_length(btrim(lift_reason)) >= 3)),
    constraint ck_hold_version check (version >= 1)
);

-- One hold per external message (CC-033 later): an agency's reference is used once per source.
create unique index uq_hold_source_ref on hold (source, source_ref) where source_ref is not null;
create index ix_hold_subject on hold (subject_type, subject_id, placed_at);

-- A hold changes only by being lifted once; nothing else changes and nothing is deleted (CC-030, CC-122).
create function hold_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'holds are never deleted; lift them (CC-030)';
    end if;
    if old.status <> 'ACTIVE' or new.status <> 'LIFTED' or new.version <> old.version + 1
       or (to_jsonb(new) - array['status', 'lifted_at', 'lifted_by', 'lifted_by_username', 'lifted_channel',
                                  'lift_reason', 'version'])
          is distinct from (to_jsonb(old) - array['status', 'lifted_at', 'lifted_by', 'lifted_by_username',
                                                   'lifted_channel', 'lift_reason', 'version']) then
        raise exception 'a hold can only be lifted once; its other fields never change (CC-030)';
    end if;
    return new;
end
$$;

create trigger trg_hold_guard before update or delete on hold
    for each row execute function hold_guard();

-- Temporal history (DAT-002): the full row image of every version, written by trigger in the same transaction.
create table hold_history (
    history_id   bigserial    primary key,
    hold_id      uuid         not null,
    version      bigint       not null,
    row_image    jsonb        not null,
    valid_from   timestamptz  not null,
    valid_to     timestamptz,
    changed_by   varchar(100) not null,
    constraint uq_hold_history_version unique (hold_id, version)
);

create function hold_history_capture() returns trigger language plpgsql as $$
declare
    changed_at timestamptz := coalesce(new.lifted_at, new.placed_at);
    changed_by varchar(100) := coalesce(new.lifted_by, new.placed_by);
begin
    update hold_history set valid_to = changed_at where hold_id = new.id and valid_to is null;
    insert into hold_history (hold_id, version, row_image, valid_from, changed_by)
    values (new.id, new.version, to_jsonb(new), changed_at, changed_by);
    return new;
end
$$;

create trigger trg_hold_history after insert or update on hold
    for each row execute function hold_history_capture();

-- History rows are closed once (valid_to) and otherwise never change or disappear.
create function hold_history_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' or old.valid_to is not null
       or (to_jsonb(new) - 'valid_to') is distinct from (to_jsonb(old) - 'valid_to') then
        raise exception 'hold history is append-only (DAT-002)';
    end if;
    return new;
end
$$;

create trigger trg_hold_history_guard before update or delete on hold_history
    for each row execute function hold_history_guard();

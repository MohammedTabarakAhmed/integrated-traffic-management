-- vehicle-service schema, part 1: vehicles and their history, inspection results, plate series and plates,
-- registration requests (VL-001..VL-041 Increment 1 core; ADR-055). Owned exclusively by this service (ADR-016).

create table vehicle (
    id                  uuid          primary key,
    vin                 char(17)      not null,
    engine_no           varchar(40)   not null,
    make                varchar(40)   not null,
    model               varchar(40)   not null,
    model_year          integer       not null,
    body_type           varchar(40)   not null,
    colours             varchar(40)[] not null,
    fuel_type           varchar(40)   not null,
    steering            varchar(5)    not null,
    seats               integer       not null,
    empty_weight_kg     integer       not null,
    gross_weight_kg     integer       not null,
    axles               integer       not null,
    cylinders           integer       not null,
    category            varchar(40)   not null,
    owner_file_number   varchar(60)   not null,
    region              varchar(40)   not null,
    status              varchar(14)   not null,
    registration_expiry date,
    plate_id            uuid,
    plate_number        varchar(20),
    created_at          timestamptz   not null,
    version             bigint        not null default 0,
    constraint ck_vehicle_vin check (vin ~ '^[A-HJ-NPR-Z0-9]{17}$'),
    constraint ck_vehicle_status check (status in ('UNREGISTERED', 'REGISTERED', 'DEREGISTERED')),
    constraint ck_vehicle_registered check (status <> 'REGISTERED'
        or (registration_expiry is not null and plate_id is not null))
);

-- VL-003: one active record per VIN.
create unique index uq_vehicle_active_vin on vehicle (vin) where status <> 'DEREGISTERED';
create index ix_vehicle_owner on vehicle (owner_file_number);

create function vehicle_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'vehicles are never deleted (VL-005)';
    end if;
    if new.vin <> old.vin or new.created_at <> old.created_at then
        raise exception 'a vehicle''s VIN never changes (VL-001)';
    end if;
    if old.status = 'DEREGISTERED' then
        raise exception 'a de-registered vehicle never changes (VL-015)';
    end if;
    return new;
end
$$;

create trigger trg_vehicle_guard before update or delete on vehicle
    for each row execute function vehicle_guard();

create table vehicle_history (
    id         bigint generated always as identity primary key,
    vehicle_id uuid          not null references vehicle (id),
    event      varchar(40)   not null,
    actor_id   varchar(100)  not null,
    detail     jsonb         not null,
    at         timestamptz   not null
);

create index ix_vehicle_history on vehicle_history (vehicle_id, id);

create table inspection_result (
    id           uuid          primary key,
    vehicle_id   uuid          not null references vehicle (id),
    centre_code  varchar(40)   not null,
    inspected_on date          not null,
    odometer_km  bigint        not null,
    results      jsonb         not null,
    passed       boolean       not null,
    photo_ids    varchar(64)[] not null,
    recorded_by  varchar(100)  not null,
    recorded_at  timestamptz   not null
);

create index ix_inspection_vehicle on inspection_result (vehicle_id, inspected_on desc, recorded_at desc);

create function vehicle_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'this table is append-only (VL-005, VL-040)';
end
$$;

create trigger trg_vehicle_history_guard before update or delete on vehicle_history
    for each row execute function vehicle_append_only();
create trigger trg_inspection_guard before update or delete on inspection_result
    for each row execute function vehicle_append_only();

create table plate_series (
    code            varchar(40)   primary key,
    region          varchar(40)   not null,
    plate_category  varchar(40)   not null,
    colour_code     varchar(40)   not null,
    design          varchar(40)   not null,
    prefix          varchar(5)    not null,
    first_number    bigint        not null,
    last_number     bigint        not null,
    next_number     bigint        not null,
    premium_pattern varchar(200),
    active          boolean       not null,
    updated_at      timestamptz   not null,
    constraint ck_series_range check (last_number >= first_number and next_number >= first_number)
);

create table plate (
    id                 uuid          primary key,
    series_code        varchar(40)   not null references plate_series (code),
    number             bigint        not null,
    display            varchar(20)   not null,
    status             varchar(10)   not null,
    vehicle_id         uuid,
    holder_file_number varchar(60),
    changed_at         timestamptz   not null,
    constraint uq_plate_number unique (series_code, number),
    constraint ck_plate_status check (status in ('AVAILABLE', 'RESERVED', 'ASSIGNED', 'RETAINED', 'RETURNED',
        'CANCELLED', 'WITHDRAWN', 'LOST'))
);

create table registration_request (
    id            uuid          primary key,
    vehicle_id    uuid          not null references vehicle (id),
    kind          varchar(8)    not null,
    status        varchar(16)   not null,
    quote_id      varchar(60),
    payable_id    uuid,
    amount_minor  bigint        not null,
    currency      char(3),
    new_expiry    date          not null,
    plate_id      uuid references plate (id),
    requested_by  varchar(100)  not null,
    channel       varchar(20)   not null,
    created_at    timestamptz   not null,
    completed_at  timestamptz,
    constraint ck_request_kind check (kind in ('FIRST', 'RENEWAL')),
    constraint ck_request_status check (status in ('AWAITING_PAYMENT', 'COMPLETED', 'CANCELLED'))
);

-- At most one request waiting for payment per vehicle.
create unique index uq_request_open on registration_request (vehicle_id) where status = 'AWAITING_PAYMENT';

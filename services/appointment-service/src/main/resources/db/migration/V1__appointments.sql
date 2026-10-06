-- appointment-service schema, part 1: centre calendars, closures, resources, service settings and bookings
-- (CC-090..CC-093; ADR-052). Owned exclusively by this service (ADR-016).

create table appointment_centre (
    code         varchar(40)  primary key,
    region       varchar(40)  not null,
    zone         varchar(60)  not null,
    weekly_hours jsonb        not null,
    active       boolean      not null,
    updated_at   timestamptz  not null
);

create table appointment_closure (
    id            bigint generated always as identity primary key,
    centre_code   varchar(40)  not null references appointment_centre (code),
    resource_code varchar(40),
    date_from     date         not null,
    date_to       date         not null,
    kind          varchar(10)  not null,
    reason        varchar(200) not null,
    created_at    timestamptz  not null default now(),
    constraint ck_closure_dates check (date_to >= date_from),
    constraint ck_closure_kind check (kind in ('HOLIDAY', 'BLACKOUT'))
);

create index ix_closure_centre on appointment_closure (centre_code, date_from, date_to);

create table appointment_resource (
    code        varchar(40)   primary key,
    centre_code varchar(40)   not null references appointment_centre (code),
    type        varchar(20)   not null,
    name        varchar(200)  not null,
    services    varchar(40)[] not null,
    capacity    integer       not null,
    active      boolean       not null,
    updated_at  timestamptz   not null,
    constraint ck_resource_type check (type in ('EXAMINER', 'INSPECTION_LANE', 'COMMITTEE', 'COUNTER')),
    constraint ck_resource_capacity check (capacity between 1 and 100)
);

create index ix_resource_centre on appointment_resource (centre_code);

create table appointment_service_settings (
    service_code        varchar(40)  primary key,
    slot_minutes        integer      not null,
    horizon_days        integer      not null,
    min_lead_minutes    integer      not null,
    max_changes         integer      not null,
    no_show_limit       integer      not null,
    no_show_window_days integer      not null,
    penalty_days        integer      not null,
    penalty_fee_code    varchar(40),
    updated_at          timestamptz  not null
);

create table booking (
    id                    uuid          primary key,
    service_code          varchar(40)   not null,
    centre_code           varchar(40)   not null references appointment_centre (code),
    resource_code         varchar(40)   not null references appointment_resource (code),
    customer_id           varchar(100)  not null,
    transaction_reference varchar(60),
    created_at            timestamptz   not null,
    start_at              timestamptz   not null,
    end_at                timestamptz   not null,
    status                varchar(10)   not null,
    changes               integer       not null,
    closed_at             timestamptz,
    reminder_sent_at      timestamptz,
    constraint ck_booking_status check (status in ('BOOKED', 'CANCELLED', 'ATTENDED', 'NO_SHOW')),
    constraint ck_booking_closed check ((status = 'BOOKED') = (closed_at is null)),
    constraint ck_booking_times check (end_at > start_at)
);

create index ix_booking_slot on booking (resource_code, start_at) where status = 'BOOKED';
create index ix_booking_centre on booking (centre_code, start_at);
create index ix_booking_customer on booking (customer_id, start_at desc);
create index ix_booking_reminder on booking (start_at) where status = 'BOOKED' and reminder_sent_at is null;

-- Closed bookings never change; bookings are never deleted (CC-091, CC-093: no-shows are evidence for penalties).
create function booking_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'bookings are never deleted (CC-091)';
    end if;
    if old.status <> 'BOOKED' then
        raise exception 'a closed booking never changes (CC-091)';
    end if;
    if new.id <> old.id or new.customer_id <> old.customer_id or new.service_code <> old.service_code
       or new.centre_code <> old.centre_code or new.created_at <> old.created_at
       or new.transaction_reference is distinct from old.transaction_reference then
        raise exception 'a booking''s customer, service and centre never change (CC-091)';
    end if;
    return new;
end
$$;

create trigger trg_booking_guard before update or delete on booking
    for each row execute function booking_guard();

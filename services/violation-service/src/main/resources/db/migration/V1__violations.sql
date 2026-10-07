-- violation-service schema, part 1: catalogue versions, violations, black points and suspensions (VF-001..VF-033,
-- VF-070 Increment 1 core; ADR-056). Owned exclusively by this service (ADR-016).

create table violation_catalogue (
    code               varchar(40)   not null,
    version_no         integer       not null,
    region             varchar(40),
    legal_article      varchar(100)  not null,
    description_nl     varchar(500)  not null,
    description_en     varchar(500)  not null,
    fine_minor         bigint        not null,
    black_points       integer       not null,
    impound_days       integer       not null,
    suspension_days    integer       not null,
    court_referral     boolean       not null,
    applies_to         varchar(7)    not null,
    speed_bands        jsonb         not null,
    repeat_within_days integer,
    repeat_fine_minor  bigint,
    financial_item     varchar(40)   not null,
    beneficiary        varchar(40)   not null,
    effective_from     date          not null,
    effective_to       date,
    published_by       varchar(100)  not null,
    published_at       timestamptz   not null,
    constraint pk_violation_catalogue primary key (code, version_no),
    constraint ck_catalogue_applies check (applies_to in ('DRIVER', 'VEHICLE', 'BOTH')),
    constraint ck_catalogue_dates check (effective_to is null or effective_to >= effective_from)
);

-- VF-002: a violation always points at the exact catalogue version; versions never change.
create function violation_catalogue_guard() returns trigger language plpgsql as $$
begin
    raise exception 'catalogue versions never change (VF-002)';
end
$$;

create trigger trg_violation_catalogue_guard before update or delete on violation_catalogue
    for each row execute function violation_catalogue_guard();

create sequence violation_seq;

create table violation (
    id                      uuid          primary key,
    number                  varchar(30)   not null unique,
    catalogue_code          varchar(40)   not null,
    catalogue_version       integer       not null,
    occurred_at             timestamptz   not null,
    region                  varchar(40)   not null,
    location                varchar(300)  not null,
    plate                   varchar(20),
    vin                     char(17),
    owner_file_number       varchar(60),
    driver_file_number      varchar(60),
    responsible_file_number varchar(60)   not null,
    speed_limit_kmh         integer,
    measured_speed_kmh      integer,
    source                  varchar(7)    not null,
    device_id               varchar(40),
    evidence_ids            varchar(64)[] not null,
    fine_minor              bigint        not null,
    black_points            integer       not null,
    repeat                  boolean       not null,
    recorded_by             varchar(100)  not null,
    recorded_at             timestamptz   not null,
    status                  varchar(9)    not null,
    payable_id              uuid,
    cancel_reason           varchar(40),
    closed_at               timestamptz,
    constraint fk_violation_catalogue foreign key (catalogue_code, catalogue_version)
        references violation_catalogue (code, version_no),
    constraint ck_violation_status check (status in ('OPEN', 'PAID', 'CANCELLED')),
    constraint ck_violation_cancel check ((status = 'CANCELLED') = (cancel_reason is not null))
);

create index ix_violation_file on violation (responsible_file_number, recorded_at desc);
create index ix_violation_repeat on violation (responsible_file_number, catalogue_code, occurred_at)
    where status <> 'CANCELLED';

-- VF-020: only status, payable and closing fields change; violations are never deleted.
create function violation_guard() returns trigger language plpgsql as $$
declare
    mutable text[] := array['status', 'payable_id', 'cancel_reason', 'closed_at'];
begin
    if tg_op = 'DELETE' then
        raise exception 'violations are never deleted (VF-020)';
    end if;
    if (to_jsonb(new) - mutable) is distinct from (to_jsonb(old) - mutable) then
        raise exception 'a violation''s facts never change (VF-020)';
    end if;
    if old.status = 'CANCELLED' then
        raise exception 'a cancelled violation never changes (VF-021)';
    end if;
    return new;
end
$$;

create trigger trg_violation_guard before update or delete on violation
    for each row execute function violation_guard();

create table black_point_entry (
    id           uuid          primary key,
    file_number  varchar(60)   not null,
    violation_id uuid          not null references violation (id),
    points       integer       not null,
    occurred_on  date          not null,
    expires_on   date          not null,
    status       varchar(9)    not null,
    constraint ck_points_status check (status in ('ACTIVE', 'CONSUMED', 'CANCELLED')),
    constraint ck_points_positive check (points > 0)
);

create index ix_points_file on black_point_entry (file_number, expires_on);

create table licence_suspension (
    id              uuid          primary key,
    file_number     varchar(60)   not null,
    ordinal         integer       not null,
    date_from       date          not null,
    date_until      date          not null,
    points_at_order integer       not null,
    hold_id         varchar(100),
    triggered_by    uuid          not null references violation (id),
    created_at      timestamptz   not null default now(),
    constraint uq_suspension_ordinal unique (file_number, ordinal),
    constraint ck_suspension_dates check (date_until >= date_from)
);

create function violation_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'suspensions are never changed or deleted (VF-032)';
end
$$;

create trigger trg_suspension_guard before update or delete on licence_suspension
    for each row execute function violation_append_only();

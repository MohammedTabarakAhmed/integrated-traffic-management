-- Registered enforcement devices, calibration history and quality-screened captures (VF-010..VF-013, VF-080..VF-082).

create table enforcement_device (
    id                    uuid          primary key,
    code                  varchar(40)   not null unique,
    type                  varchar(24)   not null,
    owner                 varchar(100)  not null,
    region                varchar(40)   not null,
    latitude              double precision not null,
    longitude             double precision not null,
    road                  varchar(120)  not null,
    direction             varchar(40)   not null,
    speed_limit_kmh       integer       not null,
    status                varchar(10)   not null,
    calibrated_at         timestamptz,
    calibration_expires_at timestamptz,
    created_at            timestamptz   not null,
    created_by            varchar(100)  not null,
    constraint ck_device_type check (type in ('RADAR', 'RED_LIGHT_CAMERA', 'MOBILE_CAMERA')),
    constraint ck_device_status check (status in ('ACTIVE', 'SUSPENDED', 'RETIRED')),
    constraint ck_device_geo check (latitude between -90 and 90 and longitude between -180 and 180),
    constraint ck_device_speed_limit check (speed_limit_kmh between 5 and 200),
    constraint ck_device_calibration check ((calibrated_at is null) = (calibration_expires_at is null)
        and (calibration_expires_at is null or calibration_expires_at > calibrated_at))
);

create table enforcement_device_calibration (
    id            uuid          primary key,
    device_id     uuid          not null references enforcement_device (id),
    calibrated_at timestamptz   not null,
    expires_at    timestamptz   not null,
    recorded_by   varchar(100)  not null,
    recorded_at   timestamptz   not null,
    constraint ck_device_calibration_period check (expires_at > calibrated_at)
);

create index ix_device_calibration_history on enforcement_device_calibration (device_id, calibrated_at desc);

create table device_capture (
    id                  uuid          primary key,
    source_key          varchar(120)  not null unique,
    device_code         varchar(40)   not null references enforcement_device (code),
    captured_at         timestamptz   not null,
    received_at         timestamptz   not null,
    catalogue_code      varchar(40)   not null,
    region              varchar(40)   not null,
    plate               varchar(20)   not null,
    location            varchar(300)  not null,
    speed_limit_kmh     integer       not null,
    measured_speed_kmh  integer,
    evidence_id         varchar(64)   not null,
    owner_file_number   varchar(60),
    status              varchar(10)   not null,
    quality_reasons     varchar(40)[] not null,
    violation_number    varchar(30),
    constraint ck_capture_status check (status in ('REVIEW', 'READY', 'RECORDED', 'REJECTED')),
    constraint ck_capture_speed check (measured_speed_kmh is null or measured_speed_kmh between 0 and 500),
    constraint ck_capture_limit check (speed_limit_kmh between 5 and 200),
    constraint ck_capture_recorded check ((status = 'RECORDED') = (violation_number is not null))
);

create index ix_capture_review on device_capture (received_at) where status = 'REVIEW';
create index ix_capture_ready on device_capture (received_at) where status = 'READY';

create function device_capture_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'device captures are never deleted (VF-010)';
    end if;
    if old.status in ('RECORDED', 'REJECTED')
       or (to_jsonb(new) - array['status', 'owner_file_number', 'violation_number'])
          is distinct from (to_jsonb(old) - array['status', 'owner_file_number', 'violation_number']) then
        raise exception 'capture facts are immutable and review is final once (VF-011)';
    end if;
    if not ((old.status in ('REVIEW', 'READY') and new.status in ('REVIEW', 'READY', 'RECORDED', 'REJECTED'))) then
        raise exception 'invalid device capture transition (VF-011)';
    end if;
    return new;
end
$$;

create trigger trg_device_capture_guard before update or delete on device_capture
    for each row execute function device_capture_guard();

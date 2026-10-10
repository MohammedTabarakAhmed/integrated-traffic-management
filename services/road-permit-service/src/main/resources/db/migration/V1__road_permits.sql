create table road_permit (
    id uuid primary key,
    number varchar(40) not null unique,
    permit_type varchar(32) not null check (permit_type in ('PARKING','OVERSIZE','OVERWEIGHT','HEAVY_RESTRICTED','ROAD_CLOSURE')),
    applicant varchar(80) not null,
    eligibility_reference varchar(120),
    region varchar(40) not null,
    status varchar(16) not null check (status in ('APPLIED','APPROVED','ISSUED','CANCELLED','EXPIRED')),
    starts_at timestamptz not null,
    ends_at timestamptz not null,
    load_tonnes double precision,
    width_metres double precision,
    height_metres double precision,
    length_metres double precision,
    vehicle_limit integer not null check (vehicle_limit > 0),
    created_by varchar(120) not null,
    created_at timestamptz not null,
    updated_at timestamptz,
    check(starts_at < ends_at)
);
create table road_permit_vehicle (
    permit_id uuid not null references road_permit(id), vehicle_no integer not null,
    vehicle_reference varchar(80) not null, primary key(permit_id,vehicle_no), unique(permit_id,vehicle_reference)
);
create table road_permit_route_segment (
    permit_id uuid not null references road_permit(id), segment_no integer not null,
    segment_id varchar(80) not null, primary key(permit_id,segment_no), unique(permit_id,segment_id)
);
create table road_permit_authority (
    permit_id uuid not null references road_permit(id), authority_code varchar(80) not null,
    status varchar(16) not null check (status in ('PENDING','APPROVED','REJECTED')),
    approved_by varchar(120), approved_at timestamptz, reason varchar(500), primary key(permit_id,authority_code)
);
create table road_permit_status_history (
    id bigint generated always as identity primary key, permit_id uuid not null references road_permit(id),
    status varchar(16) not null, changed_at timestamptz not null, changed_by varchar(120) not null, reason varchar(500)
);

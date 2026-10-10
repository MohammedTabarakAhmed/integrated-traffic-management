-- Issue and verify accident repair permits (AC-020, AC-021).
create table repair_permit (
    id uuid primary key,
    number varchar(32) not null unique,
    accident_id uuid not null references accident_report(id),
    vehicle_plate varchar(40) not null,
    region varchar(40) not null,
    issued_at timestamptz not null,
    status varchar(16) not null check (status in ('ISSUED','CANCELLED','USED')),
    code_hash varchar(64) not null
);
create index ix_repair_permit_accident on repair_permit(accident_id);
create table repair_permit_part (
    permit_id uuid not null references repair_permit(id),
    part_no integer not null,
    part_name varchar(120) not null,
    primary key (permit_id,part_no)
);

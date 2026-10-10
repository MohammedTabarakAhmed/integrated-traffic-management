create table marine_craft (
    id uuid primary key,
    hull_id varchar(80) not null unique,
    craft_type varchar(40) not null,
    length_metres double precision not null check(length_metres>0),
    engine_count integer not null check(engine_count between 1 and 10),
    capacity integer not null check(capacity>0),
    home_port varchar(80) not null,
    owner_file varchar(80) not null,
    status varchar(16) not null check(status in ('ACTIVE','SUSPENDED','DEREGISTERED')),
    registration_expires_at timestamptz,
    created_by varchar(120) not null,
    created_at timestamptz not null,
    updated_at timestamptz
);
create table marine_inspection (
    id uuid primary key,
    hull_id varchar(80) not null references marine_craft(hull_id),
    purpose varchar(20) not null check(purpose in ('REGISTRATION','RENEWAL','SAFETY')),
    result varchar(8) not null check(result in ('PASS','FAIL')),
    workshop varchar(120) not null,
    document_id varchar(80),
    inspected_at timestamptz not null,
    recorded_by varchar(120) not null
);
create table marine_operator_licence (
    id uuid primary key,
    number varchar(40) not null unique,
    traffic_file_number varchar(80) not null,
    craft_type varchar(40) not null,
    status varchar(16) not null check(status in ('ACTIVE','SUSPENDED','CANCELLED','EXPIRED')),
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    issued_by varchar(120) not null
);

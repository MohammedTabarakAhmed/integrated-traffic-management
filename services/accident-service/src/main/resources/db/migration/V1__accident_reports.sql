-- Accident reports, parties, evidence and append-only evidence references (AC-001..AC-006).
create sequence accident_report_seq start with 1;
create table accident_report (
    id uuid primary key,
    report_number varchar(24) not null unique,
    region varchar(40) not null,
    occurred_at timestamptz not null,
    location varchar(500) not null,
    road_condition varchar(80),
    weather varchar(80),
    status varchar(32) not null check (status in ('REPORTED','UNDER_INVESTIGATION','FAULT_DECIDED','CLOSED','REFERRED_TO_PROSECUTION')),
    origin varchar(24) not null check (origin in ('CUSTOMER_APP','OFFICER_DEVICE','PAPER_BOOK')),
    recorded_by varchar(100) not null,
    recorded_at timestamptz not null
);
create index ix_accident_status_time on accident_report(region,status,occurred_at desc);
create table accident_party (
    accident_id uuid not null references accident_report(id),
    party_no integer not null,
    traffic_file_number varchar(40),
    vehicle_plate varchar(40),
    role varchar(20) not null check (role in ('DRIVER','PASSENGER','PEDESTRIAN')),
    seat_position varchar(40),
    injury_degree varchar(40),
    transport_method varchar(80),
    intoxication_status varchar(40),
    fault_percent integer check (fault_percent between 0 and 100),
    agrees boolean not null,
    primary key(accident_id,party_no),
    check (traffic_file_number is not null or vehicle_plate is not null)
);
create table accident_evidence (
    accident_id uuid not null references accident_report(id),
    document_id varchar(80) not null,
    primary key(accident_id,document_id)
);

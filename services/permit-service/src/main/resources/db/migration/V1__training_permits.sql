create table permit_type (
    code varchar(40) primary key,
    category varchar(40) not null,
    licence_class varchar(40),
    minimum_driving_years integer not null check (minimum_driving_years >= 0),
    minimum_age integer not null check (minimum_age >= 16),
    clean_record_days integer not null check (clean_record_days >= 0),
    medical_fit_required boolean not null,
    test_required boolean not null,
    validity_days integer not null check (validity_days > 0),
    renewal_window_days integer not null check (renewal_window_days >= 0),
    fee_minor bigint not null check (fee_minor >= 0),
    status varchar(16) not null check (status in ('ACTIVE','INACTIVE')),
    updated_by varchar(120) not null,
    updated_at timestamptz not null
);
create table permit_number_sequence (
    year integer primary key,
    next_value integer not null check (next_value > 0)
);
create table training_permit (
    id uuid primary key,
    number varchar(40) not null unique,
    type_code varchar(40) not null references permit_type(code),
    traffic_file_number varchar(40) not null,
    school_id varchar(80),
    vehicle_plate varchar(40),
    status varchar(16) not null check (status in ('APPLIED','TESTED','APPROVED','ISSUED','SUSPENDED','CANCELLED','EXPIRED')),
    applied_at timestamptz not null,
    valid_from timestamptz,
    valid_to timestamptz,
    exception_reason varchar(500),
    created_by varchar(120) not null,
    updated_at timestamptz
);
create index training_permit_file_status on training_permit(traffic_file_number,status);
create table permit_test (
    id uuid primary key,
    permit_id uuid not null references training_permit(id),
    committee varchar(80) not null,
    examiner varchar(120),
    scheduled_at timestamptz not null,
    result varchar(16) not null check (result in ('BOOKED','PASSED','FAILED')),
    reason varchar(500),
    created_by varchar(120) not null,
    recorded_at timestamptz
);
create index permit_test_open on permit_test(permit_id,scheduled_at) where result='BOOKED';
create table permit_status_history (
    id bigint generated always as identity primary key,
    permit_id uuid not null references training_permit(id),
    status varchar(16) not null,
    changed_at timestamptz not null,
    changed_by varchar(120) not null,
    reason varchar(500)
);

create table partner_state (
    code varchar(12) primary key,
    name varchar(120) not null,
    exchange_mode varchar(16) not null check (exchange_mode in ('REALTIME','DAILY_BATCH')),
    commission_basis_points integer not null check (commission_basis_points between 0 and 10000),
    status varchar(16) not null check (status in ('ACTIVE','SUSPENDED')),
    updated_by varchar(120) not null,
    updated_at timestamptz not null
);
create table partner_code_mapping (
    partner_code varchar(12) not null references partner_state(code),
    domain varchar(40) not null,
    local_code varchar(80) not null,
    external_code varchar(80) not null,
    updated_by varchar(120) not null,
    updated_at timestamptz not null,
    primary key(partner_code,domain,local_code),
    unique(partner_code,domain,external_code)
);
create table exchange_message (
    id uuid primary key,
    partner_code varchar(12) not null references partner_state(code),
    external_id varchar(120),
    message_type varchar(24) not null check (message_type in ('VIOLATION','PAYMENT','OBJECTION','CODE_MAPPING')),
    direction varchar(8) not null check (direction in ('INBOUND','OUTBOUND')),
    status varchar(16) not null check (status in ('PENDING','RECEIVED','SENT','PAID','OBJECTED','REJECTED')),
    local_reference varchar(120),
    external_code varchar(80),
    mapped_code varchar(80),
    vehicle_plate varchar(40),
    owner_file varchar(40),
    currency char(3),
    amount_minor bigint not null default 0 check (amount_minor >= 0),
    occurred_at timestamptz not null,
    received_at timestamptz not null,
    detail varchar(1000),
    updated_at timestamptz,
    unique(partner_code,external_id)
);
create index exchange_message_queue on exchange_message(partner_code,status,occurred_at);
create index exchange_message_settlement on exchange_message(partner_code,occurred_at) where status='PAID';

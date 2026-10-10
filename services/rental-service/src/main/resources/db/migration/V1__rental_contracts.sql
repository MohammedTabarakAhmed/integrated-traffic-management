-- Rental companies and timestamped contracts (TR-020..TR-033).
create table rental_company (
    id uuid primary key,
    traffic_file_number varchar(40) not null unique,
    commercial_licence varchar(100) not null unique,
    region varchar(40) not null,
    status varchar(20) not null check (status in ('ACTIVE','SUSPENDED')),
    created_at timestamptz not null,
    created_by varchar(100) not null
);
create table rental_contract (
    id uuid primary key,
    company_file varchar(40) not null references rental_company(traffic_file_number),
    vehicle_plate varchar(40) not null,
    renter_file varchar(40),
    visitor_passport varchar(100),
    foreign_licence varchar(100),
    starts_at timestamptz not null,
    ends_at timestamptz not null,
    pickup_branch varchar(100) not null,
    return_branch varchar(100) not null,
    contract_type varchar(24) not null check (contract_type in ('DAILY','MONTHLY','LONG_TERM_LEASE','LEASE_TO_OWN')),
    bond_minor bigint not null check (bond_minor >= 0),
    status varchar(20) not null check (status in ('ACTIVE','CLOSED','CANCELLED')),
    created_at timestamptz not null,
    created_by varchar(100) not null,
    check (starts_at < ends_at),
    check ((renter_file is not null and visitor_passport is null and foreign_licence is null)
        or (renter_file is null and visitor_passport is not null and foreign_licence is not null))
);
create table rental_contract_driver (
    contract_id uuid not null references rental_contract(id),
    driver_no integer not null,
    traffic_file_number varchar(40) not null,
    primary key (contract_id, driver_no),
    unique (contract_id, traffic_file_number)
);
create index ix_rental_contract_vehicle_period on rental_contract(vehicle_plate, starts_at, ends_at);
create index ix_rental_contract_renter_period on rental_contract(renter_file, starts_at, ends_at);

-- vehicle-service schema, part 2 (Increment 2; ADR-059): vehicle transactions and plates complete.
-- Paid transactions reuse registration_request (kind + payload); new records: customs/dealer certificates (VL-002),
-- banned vehicles (VL-006), approvals (VL-018, VL-019), certificates (VL-017, VL-020), mortgages (VL-021),
-- registration cards (VL-014, VL-022), temporary entry/exit permits (VL-016), fleet driver assignments (VL-026),
-- plate auctions (VL-034), plate manufacturing orders (VL-036), plate loss reports (VL-037), trial plates (VL-038).

-- ---- paid transactions of every kind
alter table registration_request drop constraint ck_request_kind;
alter table registration_request add constraint ck_request_kind check (kind in ('FIRST', 'RENEWAL', 'TRANSFER',
    'CHANGE', 'CARD', 'DEREGISTER', 'CERTIFICATE', 'TEMP_PERMIT', 'PLATE_RESERVATION', 'PLATE_RETENTION',
    'PLATE_SALE', 'PLATE_EXCHANGE', 'PLATE_REPLACEMENT', 'TRIAL_PLATE', 'AUCTION'));
alter table registration_request alter column kind type varchar(20);
-- A transfer waits for the buyer's consent before its fee is asked (VL-012).
alter table registration_request drop constraint ck_request_status;
alter table registration_request add constraint ck_request_status check (status in ('AWAITING_CONSENT',
    'AWAITING_PAYMENT', 'COMPLETED', 'CANCELLED'));
drop index uq_request_open;
create unique index uq_request_open on registration_request (vehicle_id)
    where status in ('AWAITING_CONSENT', 'AWAITING_PAYMENT');
alter table registration_request alter column vehicle_id drop not null;
alter table registration_request alter column new_expiry drop not null;
alter table registration_request add column payload jsonb not null default '{}';
alter table registration_request add column subject_ref varchar(60);
alter table registration_request add column payer_file varchar(60);
create index ix_request_subject on registration_request (subject_ref) where subject_ref is not null;

-- ---- vehicles: the customs or dealer certificate that pre-filled the record (VL-002, VL-010)
alter table vehicle add column customs_certificate_no varchar(60);

-- ---- plates: a number is free again once its last row is RETURNED, CANCELLED or AVAILABLE (VL-033, VL-039);
-- earlier rows stay as the plate's history.
alter table plate drop constraint uq_plate_number;
create unique index uq_plate_number_live on plate (series_code, number)
    where status in ('RESERVED', 'ASSIGNED', 'RETAINED', 'WITHDRAWN', 'LOST');
alter table plate add column hold_until date;
alter table plate add column serial_no varchar(40);
create index ix_plate_holder on plate (holder_file_number, status);
create index ix_plate_hold_until on plate (status, hold_until) where hold_until is not null;

create table customs_certificate (
    certificate_no varchar(60)  primary key,
    source         varchar(10)  not null,
    issuer_code    varchar(40)  not null,
    vin            char(17)     not null,
    spec           jsonb        not null,
    issued_on      date         not null,
    received_by    varchar(100) not null,
    received_at    timestamptz  not null,
    used_by        uuid references vehicle (id),
    constraint ck_customs_source check (source in ('CUSTOMS', 'DEALER'))
);
create index ix_customs_vin on customs_certificate (vin);

create table vehicle_ban (
    id           uuid         primary key,
    vehicle_id   uuid         not null references vehicle (id),
    reason_code  varchar(40)  not null,
    reference    varchar(60)  not null,
    imposed_by   varchar(100) not null,
    imposed_at   timestamptz  not null,
    lifted_by    varchar(100),
    lifted_at    timestamptz,
    lift_reason  varchar(500),
    constraint ck_ban_lift check ((lifted_at is null) = (lifted_by is null))
);
create index ix_ban_vehicle on vehicle_ban (vehicle_id) where lifted_at is null;

create table vehicle_approval (
    id           uuid         primary key,
    vehicle_id   uuid         not null references vehicle (id),
    kind         varchar(30)  not null,
    status       varchar(10)  not null,
    detail       jsonb        not null,
    submitted_by varchar(100) not null,
    submitted_at timestamptz  not null,
    decided_by   varchar(100),
    decided_at   timestamptz,
    decision_note varchar(500),
    constraint ck_approval_kind check (kind in ('PUBLIC_DECLARATION', 'SECURITY_INSPECTION')),
    constraint ck_approval_status check (status in ('SUBMITTED', 'APPROVED', 'REJECTED'))
);
create index ix_approval_vehicle on vehicle_approval (vehicle_id, kind);

create table vehicle_certificate (
    id          uuid         primary key,
    number      varchar(30)  not null unique,
    vehicle_id  uuid         not null references vehicle (id),
    kind        varchar(30)  not null,
    content     jsonb        not null,
    issued_by   varchar(100) not null,
    issued_at   timestamptz  not null,
    constraint ck_certificate_kind check (kind in ('OWNERSHIP', 'NO_OBJECTION', 'REGISTRATION_HISTORY', 'CLEARANCE',
        'EXPORT', 'INSURANCE_RECOVERY'))
);
create index ix_certificate_vehicle on vehicle_certificate (vehicle_id);
create sequence vehicle_certificate_seq;

create table mortgage (
    id              uuid         primary key,
    vehicle_id      uuid         not null references vehicle (id),
    bank_code       varchar(40)  not null,
    contract_ref    varchar(60)  not null,
    debtor_file     varchar(60)  not null,
    status          varchar(10)  not null,
    transfer_consent uuid,
    registered_by   varchar(100) not null,
    registered_at   timestamptz  not null,
    released_by     varchar(100),
    released_at     timestamptz,
    constraint ck_mortgage_status check (status in ('ACTIVE', 'RELEASED'))
);
create unique index uq_mortgage_contract on mortgage (bank_code, contract_ref);
create index ix_mortgage_vehicle on mortgage (vehicle_id) where status = 'ACTIVE';

create table vehicle_card (
    id              uuid         primary key,
    vehicle_id      uuid         not null references vehicle (id),
    serial          integer      not null,
    status          varchar(12)  not null,
    delivery        varchar(10)  not null,
    address         varchar(300),
    issued_at       timestamptz  not null,
    changed_at      timestamptz  not null,
    tracking_ref    varchar(60),
    constraint uq_card_serial unique (vehicle_id, serial),
    constraint ck_card_status check (status in ('PRINT_QUEUED', 'PRINTED', 'DISPATCHED', 'DELIVERED', 'COLLECTED',
        'ACTIVE_DIGITAL', 'CANCELLED')),
    constraint ck_card_delivery check (delivery in ('CENTRE', 'COURIER', 'DIGITAL'))
);

create table temporary_permit (
    id               uuid         primary key,
    number           varchar(30)  not null unique,
    kind             varchar(5)   not null,
    status           varchar(18)  not null,
    vehicle_id       uuid references vehicle (id),
    vin              char(17)     not null,
    foreign_plate    varchar(20),
    country          varchar(3)   not null,
    holder_file      varchar(60)  not null,
    border_point     varchar(40)  not null,
    valid_from       date         not null,
    valid_to         date         not null,
    region           varchar(40)  not null,
    created_by       varchar(100) not null,
    created_at       timestamptz  not null,
    closed_at        timestamptz,
    constraint ck_permit_kind check (kind in ('ENTRY', 'EXIT')),
    constraint ck_permit_status check (status in ('AWAITING_PAYMENT', 'ACTIVE', 'CLOSED', 'CANCELLED')),
    constraint ck_permit_dates check (valid_to >= valid_from)
);
create sequence temporary_permit_seq;

create table driver_assignment (
    id           uuid         primary key,
    vehicle_id   uuid         not null references vehicle (id),
    company_file varchar(60)  not null,
    driver_file  varchar(60)  not null,
    valid_from   date         not null,
    valid_to     date,
    assigned_by  varchar(100) not null,
    assigned_at  timestamptz  not null,
    ended_by     varchar(100),
    ended_at     timestamptz
);
create unique index uq_assignment_open on driver_assignment (vehicle_id) where ended_at is null;

create table plate_auction (
    id          uuid         primary key,
    title       varchar(200) not null,
    sales_channel varchar(10) not null,
    partner_code varchar(40),
    starts_at   timestamptz  not null,
    ends_at     timestamptz  not null,
    status      varchar(10)  not null,
    region      varchar(40)  not null,
    created_by  varchar(100) not null,
    created_at  timestamptz  not null,
    closed_by   varchar(100),
    closed_at   timestamptz,
    constraint ck_auction_channel check (sales_channel in ('ONLINE', 'PARTNER')),
    constraint ck_auction_status check (status in ('OPEN', 'CLOSED')),
    constraint ck_auction_dates check (ends_at > starts_at)
);

create table auction_lot (
    auction_id     uuid    not null references plate_auction (id),
    plate_id       uuid    not null references plate (id),
    reserve_minor  bigint  not null,
    status         varchar(12) not null,
    winner_file    varchar(60),
    winning_minor  bigint,
    primary key (auction_id, plate_id),
    constraint ck_lot_status check (status in ('OPEN', 'SOLD', 'UNSOLD', 'PAID'))
);

create table auction_bid (
    id          uuid         primary key,
    auction_id  uuid         not null references plate_auction (id),
    plate_id    uuid         not null references plate (id),
    bidder_file varchar(60)  not null,
    amount_minor bigint      not null,
    source      varchar(10)  not null,
    placed_by   varchar(100) not null,
    placed_at   timestamptz  not null
);
create index ix_bid_lot on auction_bid (auction_id, plate_id, amount_minor desc);

create table plate_order (
    id           uuid         primary key,
    factory_code varchar(40)  not null,
    status       varchar(14)  not null,
    raised_by    varchar(100) not null,
    raised_at    timestamptz  not null,
    changed_at   timestamptz  not null,
    constraint ck_order_status check (status in ('ORDERED', 'IN_PRODUCTION', 'DELIVERED', 'CANCELLED'))
);

-- One physical plate (pair) to make: queued until an order takes it (VL-036).
create table plate_order_item (
    id         uuid         primary key,
    plate_id   uuid         not null references plate (id),
    order_id   uuid references plate_order (id),
    reason     varchar(20)  not null,
    serial_no  varchar(40),
    created_at timestamptz  not null
);
create index ix_order_item_pending on plate_order_item (created_at) where order_id is null;

create table plate_loss_report (
    id           uuid         primary key,
    plate_id     uuid         not null references plate (id),
    kind         varchar(7)   not null,
    police_ref   varchar(60)  not null,
    replacement  varchar(6)   not null,
    status       varchar(18)  not null,
    new_plate_id uuid references plate (id),
    reported_by  varchar(100) not null,
    reported_at  timestamptz  not null,
    constraint ck_loss_kind check (kind in ('LOST', 'STOLEN')),
    constraint ck_loss_replacement check (replacement in ('SAME', 'NEW')),
    constraint ck_loss_status check (status in ('AWAITING_PAYMENT', 'REPLACED'))
);

create table trial_plate (
    id          uuid         primary key,
    plate_id    uuid         not null references plate (id),
    dealer_file varchar(60)  not null,
    region      varchar(40)  not null,
    status      varchar(18)  not null,
    valid_from  date,
    valid_to    date,
    days        integer      not null,
    replaces    uuid references trial_plate (id),
    created_by  varchar(100) not null,
    created_at  timestamptz  not null,
    changed_at  timestamptz  not null,
    constraint ck_trial_status check (status in ('AWAITING_PAYMENT', 'ACTIVE', 'EXCHANGED', 'CANCELLED', 'EXPIRED'))
);
create index ix_trial_dealer on trial_plate (dealer_file, status);

-- Bans, certificates, bids and approvals' submissions are records of what happened: never deleted.
create trigger trg_certificate_guard before update or delete on vehicle_certificate
    for each row execute function vehicle_append_only();
create trigger trg_bid_guard before update or delete on auction_bid
    for each row execute function vehicle_append_only();

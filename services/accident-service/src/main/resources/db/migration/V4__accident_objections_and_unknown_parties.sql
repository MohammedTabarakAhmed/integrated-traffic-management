alter table accident_party add column unknown_party boolean not null default false;
do $$
declare constraint_row record;
begin
    for constraint_row in
        select conname from pg_constraint
        where conrelid = 'accident_party'::regclass and contype = 'c'
          and pg_get_constraintdef(oid) like '%traffic_file_number%'
    loop
        execute format('alter table accident_party drop constraint %I', constraint_row.conname);
    end loop;
end $$;
alter table accident_party add constraint ck_accident_party_identity
    check (unknown_party or traffic_file_number is not null or vehicle_plate is not null);
alter table accident_party add constraint ck_accident_unknown_party_identity
    check (not unknown_party or (traffic_file_number is null and vehicle_plate is null));

create table accident_objection (
    id uuid primary key,
    accident_id uuid not null references accident_report(id),
    party_no integer not null,
    reason varchar(1000) not null,
    filed_at timestamptz not null,
    deadline_at timestamptz not null,
    status varchar(16) not null check (status in ('PENDING','ACCEPTED','REJECTED')),
    reviewed_by varchar(120),
    reviewed_at timestamptz,
    review_reason varchar(1000),
    foreign key(accident_id,party_no) references accident_party(accident_id,party_no)
);
create unique index uq_accident_pending_objection on accident_objection(accident_id,party_no) where status='PENDING';
create index ix_accident_objection_due on accident_objection(status,deadline_at);

create table accident_paper_book (
    serial varchar(40) primary key,
    region varchar(40) not null,
    officer_id varchar(120) not null,
    status varchar(16) not null check (status in ('ISSUED','RECONCILED','LOST')),
    issued_at timestamptz not null,
    reconciled_at timestamptz,
    reconciled_by varchar(120)
);
create table accident_paper_book_report (
    book_serial varchar(40) not null references accident_paper_book(serial),
    report_serial varchar(40) not null,
    accident_id uuid not null unique references accident_report(id),
    entered_at timestamptz not null,
    primary key(book_serial,report_serial)
);
create table accident_external_exchange (
    id uuid primary key,
    accident_id uuid not null references accident_report(id),
    partner varchar(24) not null check (partner in ('AMBULANCE','INSURER','PROSECUTION')),
    direction varchar(8) not null check (direction in ('OUTBOUND','INBOUND')),
    reference varchar(120),
    payload jsonb not null default '{}'::jsonb,
    status varchar(16) not null check (status in ('QUEUED','ACKNOWLEDGED','FAILED')),
    created_at timestamptz not null,
    updated_at timestamptz
);
create index ix_accident_external_exchange on accident_external_exchange(accident_id,partner,created_at);

create table rental_branch (
    company_file varchar(40) not null references rental_company(traffic_file_number),
    branch_code varchar(40) not null,
    name varchar(120) not null,
    region varchar(40) not null,
    active boolean not null default true,
    created_at timestamptz not null,
    created_by varchar(100) not null,
    primary key(company_file,branch_code)
);
create index ix_rental_branch_active on rental_branch(company_file,active,branch_code);
alter table rental_company add column status_reason varchar(500);
alter table rental_company add column status_changed_at timestamptz;
alter table rental_company add column status_changed_by varchar(100);

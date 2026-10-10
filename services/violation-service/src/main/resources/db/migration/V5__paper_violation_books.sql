create table violation_paper_book (
    serial varchar(40) primary key,
    region varchar(40) not null,
    officer_id varchar(100) not null,
    status varchar(16) not null check (status in ('ISSUED','RECONCILED','LOST')),
    issued_at timestamptz not null,
    reconciled_at timestamptz,
    reconciled_by varchar(100)
);
create table violation_paper_book_entry (
    book_serial varchar(40) not null references violation_paper_book(serial),
    report_serial varchar(40) not null,
    violation_number varchar(30) not null unique references violation(number),
    entered_at timestamptz not null,
    primary key(book_serial,report_serial)
);
create index ix_violation_book_status on violation_paper_book(region,status,issued_at);

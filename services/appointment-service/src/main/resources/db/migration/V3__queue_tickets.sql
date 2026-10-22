-- CH-031: centre queue management (appointment-service, ADR-070): walk-up tickets issued per centre and day, called
-- to a counter, then served or the customer leaves. Waiting time and service time are spans of the ticket's own
-- times; nothing downstream changes (no holds, no fees, no bookings here). Issued tickets and closed states never
-- disappear; the ledger of arrivals stays in the audit record.

create table queue_ticket (
    id           uuid primary key,
    ticket_no    varchar(30)  not null unique,
    centre_code  varchar(40)  not null references appointment_centre (code),
    service_code varchar(40),
    issued_by    varchar(100) not null,
    issued_at    timestamptz  not null,
    status       varchar(10)  not null check (status in ('ISSUED', 'CALLED', 'SERVED', 'LEFT')),
    counter      varchar(40),
    called_at    timestamptz,
    finished_at  timestamptz,
    left_at      timestamptz,
    left_reason  varchar(200),
    constraint ck_queue_ticket_states check (
        (status = 'ISSUED' and counter is null and called_at is null)
        or (status = 'CALLED' and counter is not null and called_at is not null)
        or (status = 'SERVED' and called_at is not null and finished_at is not null)
        or (status = 'LEFT' and left_at is not null and left_reason is not null))
);

create index ix_queue_centre_day on queue_ticket (centre_code, issued_at);

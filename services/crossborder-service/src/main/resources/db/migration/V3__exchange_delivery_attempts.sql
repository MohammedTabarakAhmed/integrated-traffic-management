-- Durable outbound delivery state supports acknowledgement and bounded retries (XB-001, XB-008).

create table exchange_delivery (
    message_id       uuid          primary key references exchange_message(id),
    attempt_count    integer       not null default 0,
    next_attempt_at timestamptz   not null,
    last_attempt_at timestamptz,
    acknowledged_at timestamptz,
    status           varchar(16)   not null default 'PENDING',
    last_response    varchar(500),
    constraint ck_exchange_delivery_attempts check (attempt_count between 0 and 8),
    constraint ck_exchange_delivery_status check (status in ('PENDING', 'ACKNOWLEDGED', 'FAILED')),
    constraint ck_exchange_delivery_ack check ((status = 'ACKNOWLEDGED') = (acknowledged_at is not null))
);

insert into exchange_delivery(message_id,next_attempt_at)
select id,received_at from exchange_message where direction='OUTBOUND' and status='PENDING';

create index ix_exchange_delivery_due on exchange_delivery(next_attempt_at,message_id)
    where status='PENDING' and attempt_count < 8;

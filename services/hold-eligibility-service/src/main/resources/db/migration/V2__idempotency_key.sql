-- hold-eligibility-service schema, part 2: Idempotency-Key store (API_SPECIFICATION §2, DATA_MODEL §6; ADR-032).
-- Used by itms-platform's IdempotencyFilter. A key belongs to one principal; a row is either an open claim
-- (response_status null) or a completed request whose response is replayed until expires_at. Expired rows are
-- deleted by the clean-up job.
create table idempotency_key (
    principal_id          varchar(100)  not null,
    idempotency_key       varchar(128)  not null,
    request_hash          char(64)      not null,
    lock_token            varchar(36)   not null,
    locked_at             timestamptz   not null,
    expires_at            timestamptz   not null,
    response_status       integer,
    response_content_type varchar(200),
    response_location     varchar(1000),
    response_body         bytea,
    completed_at          timestamptz,
    constraint pk_idempotency_key primary key (principal_id, idempotency_key),
    constraint ck_idempotency_key_format check (idempotency_key ~ '^[A-Za-z0-9._:-]{8,128}$'),
    constraint ck_idempotency_response check (
        (response_status is null and response_body is null and completed_at is null)
        or (response_status between 200 and 299 and completed_at is not null)),
    constraint ck_idempotency_expiry check (expires_at > locked_at)
);

create index ix_idempotency_key_expires on idempotency_key (expires_at);

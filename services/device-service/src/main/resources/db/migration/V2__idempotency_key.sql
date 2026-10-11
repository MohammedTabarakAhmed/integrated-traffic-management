-- Idempotency-Key replay store of the platform filter (ADR-032); every changing POST needs it.
create table idempotency_key (
    principal_id varchar(100) not null, idempotency_key varchar(128) not null, request_hash char(64) not null,
    lock_token varchar(36) not null, locked_at timestamptz not null, expires_at timestamptz not null,
    response_status integer, response_content_type varchar(200), response_location varchar(1000), response_body bytea,
    completed_at timestamptz, primary key(principal_id,idempotency_key),
    check(idempotency_key ~ '^[A-Za-z0-9._:-]{8,128}$'),
    check((response_status is null and response_body is null and completed_at is null) or (response_status between 200 and 299 and completed_at is not null)),
    check(expires_at>locked_at)
);
create index ix_idempotency_key_expires on idempotency_key(expires_at);

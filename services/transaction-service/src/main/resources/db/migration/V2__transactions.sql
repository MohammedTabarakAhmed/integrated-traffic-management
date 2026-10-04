-- transaction-service schema, part 2: transactions (CC-021, CC-022, CC-023, CC-027, CC-113; ADR-029).
-- Owned exclusively by this service (ADR-016). Expiry (CC-024) and tracking (CC-025) come in later slices.

-- CC-021 / Q-003: one counter per service, region and year. A counter only ever moves up by one, inside the
-- transaction that opens the transaction it numbers, so a rolled-back attempt gives its number back.
create table transaction_reference_sequence (
    service_code varchar(20)  not null references service_definition (code),
    region       varchar(40)  not null,
    year         smallint     not null,
    last_value   bigint       not null,
    primary key (service_code, region, year),
    constraint ck_sequence_region check (region ~ '^[A-Z0-9_]{1,40}$'),
    constraint ck_sequence_year check (year between 1 and 9999),
    constraint ck_sequence_value check (last_value between 1 and 9999999)
);

create function transaction_reference_sequence_guard() returns trigger language plpgsql as $$
begin
    if new.service_code <> old.service_code or new.region <> old.region or new.year <> old.year
       or new.last_value <> old.last_value + 1 then
        raise exception 'a reference sequence only moves up by one (CC-021)';
    end if;
    return new;
end
$$;

create trigger trg_transaction_reference_sequence_guard before update on transaction_reference_sequence
    for each row execute function transaction_reference_sequence_guard();

create table service_transaction (
    id                     uuid          primary key,
    reference              varchar(74)   not null unique,
    service_code           varchar(20)   not null references service_definition (code),
    reference_year         smallint      not null,
    reference_sequence     bigint        not null,
    -- The definition the transaction was opened under (CC-020): version, module, fee and the CC-027 flag.
    service_version        bigint        not null,
    module                 varchar(40)   not null,
    fee_code               varchar(40),
    payment_required       boolean       not null,
    allows_concurrent_open boolean       not null,
    subject_type           varchar(20)   not null,
    subject_id             varchar(40)   not null,
    applicant_file_number  varchar(40)   not null,
    home_region            varchar(40)   not null,
    region                 varchar(40)   not null,
    channel                varchar(20)   not null,
    created_at             timestamptz   not null,
    created_by             varchar(100)  not null,
    created_by_username    varchar(200),
    submitted_at           timestamptz,
    -- Current status and the change that set it; the trigger below copies every change into the history (CC-023).
    status                 varchar(20)   not null,
    status_reason          varchar(30)   not null,
    status_note            varchar(500),
    status_actor_id        varchar(100)  not null,
    status_actor_username  varchar(200),
    status_channel         varchar(20)   not null,
    status_changed_at      timestamptz   not null,
    version                bigint        not null,
    -- CC-021: <ServiceCode>-<Region>-<YYYY>-<Sequence>, 7 digits (Q-003).
    constraint ck_transaction_reference check (reference = service_code || '-' || region || '-'
        || lpad(reference_year::text, 4, '0') || '-' || lpad(reference_sequence::text, 7, '0')),
    constraint ck_transaction_sequence check (reference_sequence between 1 and 9999999
        and reference_year between 1 and 9999),
    constraint ck_transaction_codes check (module ~ '^[A-Z0-9_]{1,40}$'
        and (fee_code is null or fee_code ~ '^[A-Z0-9_]{1,40}$')
        and home_region ~ '^[A-Z0-9_]{1,40}$' and region ~ '^[A-Z0-9_]{1,40}$'),
    constraint ck_transaction_payment check (payment_required = (fee_code is not null)),
    constraint ck_transaction_subject check (subject_type in ('TRAFFIC_FILE', 'VEHICLE', 'DRIVING_LICENCE', 'PLATE')
        and subject_id ~ '^[A-Z0-9][A-Z0-9-]{0,39}$'
        and applicant_file_number ~ '^[A-Z0-9][A-Z0-9-]{0,39}$'),
    constraint ck_transaction_channels check (
        channel in ('PORTAL', 'APP', 'BACKOFFICE', 'PARTNER', 'KIOSK', 'CALLCENTRE', 'FIELD', 'ASSISTANT', 'AGENT')
        and status_channel in ('PORTAL', 'APP', 'BACKOFFICE', 'PARTNER', 'KIOSK', 'CALLCENTRE', 'FIELD', 'ASSISTANT',
                               'AGENT')),
    constraint ck_transaction_status check (status in ('DRAFT', 'SUBMITTED', 'PENDING_PAYMENT', 'PAID', 'IN_REVIEW',
        'RETURNED_FOR_INFO', 'APPROVED', 'REJECTED', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    constraint ck_transaction_reason check (status_reason in ('CREATED', 'SUBMITTED', 'PAYMENT_REQUIRED',
        'ZERO_FEE_PAYMENT_SKIPPED', 'PAYMENT_CONFIRMED', 'REVIEW_STARTED', 'RETURNED_FOR_INFO', 'INFO_PROVIDED',
        'APPROVED', 'REJECTED', 'FULFILLED', 'CANCELLED', 'EXPIRED')),
    constraint ck_transaction_note check (status_note is null or char_length(txn_strip(status_note)) >= 3),
    constraint ck_transaction_version check (version >= 1),
    -- A new transaction starts in Draft (CC-022).
    constraint ck_transaction_created check (version > 1 or (status = 'DRAFT' and status_reason = 'CREATED')),
    -- A Draft was never submitted; anything past Draft was, except a Draft that was cancelled or expired.
    constraint ck_transaction_submitted check ((status <> 'DRAFT' or submitted_at is null)
        and (submitted_at is not null or status in ('DRAFT', 'CANCELLED', 'EXPIRED')))
);

-- CC-027: one open transaction of a service per subject, unless the service allowed several when it was opened.
-- Open = not Completed, Rejected, Cancelled or Expired (Q-047).
create unique index uq_transaction_open_subject on service_transaction (service_code, subject_type, subject_id)
    where status not in ('COMPLETED', 'REJECTED', 'CANCELLED', 'EXPIRED') and not allows_concurrent_open;
create index ix_transaction_subject on service_transaction (subject_type, subject_id, service_code);

-- CC-022 / LLD §2.1: only the lifecycle's transitions, one at a time; everything except the status and the change
-- that set it is fixed once the transaction is opened (submitted_at is set once, on submission).
create function service_transaction_guard() returns trigger language plpgsql as $$
begin
    if (to_jsonb(new) - array['status', 'status_reason', 'status_note', 'status_actor_id', 'status_actor_username',
                              'status_channel', 'status_changed_at', 'version', 'submitted_at'])
       is distinct from
       (to_jsonb(old) - array['status', 'status_reason', 'status_note', 'status_actor_id', 'status_actor_username',
                              'status_channel', 'status_changed_at', 'version', 'submitted_at']) then
        raise exception 'only the status of a transaction can change (CC-022)';
    end if;
    if new.version <> old.version + 1 or new.status_changed_at < old.status_changed_at then
        raise exception 'a status change must follow the previous one (CC-023)';
    end if;
    if (old.status, new.status) not in (
            ('DRAFT', 'SUBMITTED'), ('DRAFT', 'EXPIRED'), ('DRAFT', 'CANCELLED'),
            ('SUBMITTED', 'PENDING_PAYMENT'), ('SUBMITTED', 'IN_REVIEW'),
            ('PENDING_PAYMENT', 'PAID'), ('PENDING_PAYMENT', 'EXPIRED'), ('PENDING_PAYMENT', 'CANCELLED'),
            ('PAID', 'IN_REVIEW'),
            ('IN_REVIEW', 'RETURNED_FOR_INFO'), ('IN_REVIEW', 'APPROVED'), ('IN_REVIEW', 'REJECTED'),
            ('RETURNED_FOR_INFO', 'IN_REVIEW'), ('RETURNED_FOR_INFO', 'EXPIRED'),
            ('APPROVED', 'COMPLETED')) then
        raise exception 'transition % -> % is not in the lifecycle (CC-022)', old.status, new.status;
    end if;
    -- Q-004: only a service without a fee skips the payment steps, and only a service with one waits for payment.
    if old.status = 'SUBMITTED' and (new.status = 'IN_REVIEW') = new.payment_required then
        raise exception 'payment routing does not match the fee (CC-022, Q-004)';
    end if;
    if (old.submitted_at is not null and new.submitted_at is distinct from old.submitted_at)
       or (old.submitted_at is null and new.submitted_at is not null and new.status <> 'SUBMITTED') then
        raise exception 'submitted_at is set once, on submission (CC-022)';
    end if;
    return new;
end
$$;

create trigger trg_service_transaction_guard before update on service_transaction
    for each row execute function service_transaction_guard();

-- CC-023: every status change with user, channel, time, reason and previous status. Written by trigger in the same
-- transaction, so application code cannot skip it; append-only.
create table transaction_status_history (
    id              bigserial     primary key,
    transaction_id  uuid          not null references service_transaction (id),
    sequence        bigint        not null,
    previous_status varchar(20),
    status          varchar(20)   not null,
    reason          varchar(30)   not null,
    note            varchar(500),
    actor_id        varchar(100)  not null,
    actor_username  varchar(200),
    channel         varchar(20)   not null,
    changed_at      timestamptz   not null,
    constraint uq_status_history_sequence unique (transaction_id, sequence),
    constraint ck_status_history_first check ((sequence = 1) = (previous_status is null))
);

create function service_transaction_history_capture() returns trigger language plpgsql as $$
begin
    insert into transaction_status_history (transaction_id, sequence, previous_status, status, reason, note, actor_id,
                                            actor_username, channel, changed_at)
    values (new.id, new.version, case when tg_op = 'UPDATE' then old.status end, new.status, new.status_reason,
            new.status_note, new.status_actor_id, new.status_actor_username, new.status_channel,
            new.status_changed_at);
    return new;
end
$$;

create trigger trg_service_transaction_history
    after insert or update on service_transaction
    for each row execute function service_transaction_history_capture();

create function transaction_append_only() returns trigger language plpgsql as $$
begin
    raise exception '% is append-only (CC-023, DAT-002)', tg_table_name;
end
$$;

create trigger trg_status_history_no_update before update on transaction_status_history
    for each row execute function transaction_append_only();
create trigger trg_status_history_no_delete before delete on transaction_status_history
    for each row execute function transaction_append_only();
create trigger trg_service_transaction_no_delete before delete on service_transaction
    for each row execute function transaction_append_only();
create trigger trg_transaction_reference_sequence_no_delete before delete on transaction_reference_sequence
    for each row execute function transaction_append_only();

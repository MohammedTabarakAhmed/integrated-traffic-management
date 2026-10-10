-- licensing-service schema, part 4: examination transfers, driving licences, their history and the paid licensing
-- requests (DL-021…023, DL-036, DL-050, DL-062).

-- A passed examination file handed over by examination-service (DL-036); used once by a licence.
create table examination_transfer (
    id                   uuid          primary key,
    examination_file_ref varchar(60)   not null,
    traffic_file_number  varchar(60)   not null,
    class_code           varchar(20)   not null,
    gearbox              varchar(10)   not null,
    passed_on            date          not null,
    region               varchar(40)   not null,
    status               varchar(12)   not null,
    licence_id           uuid,
    received_at          timestamptz   not null,
    received_by          varchar(100)  not null,
    reversal_reason      varchar(500),
    reversed_by          varchar(100),
    changed_at           timestamptz,
    constraint uq_examination_transfer unique (examination_file_ref, class_code),
    constraint ck_transfer_gearbox check (gearbox in ('MANUAL', 'AUTOMATIC')),
    constraint ck_transfer_status check (status in ('TRANSFERRED', 'REVERSED', 'USED')),
    constraint ck_transfer_used check ((status = 'USED') = (licence_id is not null)),
    constraint ck_transfer_reversed check ((status = 'REVERSED') = (reversal_reason is not null))
);

create index ix_examination_transfer_holder on examination_transfer (traffic_file_number, status);

-- Only an open transfer changes, once; never deleted.
create function examination_transfer_guard() returns trigger language plpgsql as $$
declare
    mutable text[] := array['status', 'licence_id', 'reversal_reason', 'reversed_by', 'changed_at'];
begin
    if tg_op = 'DELETE' then
        raise exception 'examination transfers are never deleted (DL-036)';
    end if;
    if old.status <> 'TRANSFERRED' or (to_jsonb(new) - mutable) is distinct from (to_jsonb(old) - mutable) then
        raise exception 'an examination transfer is only used or reversed once (DL-036)';
    end if;
    return new;
end
$$;

create trigger trg_examination_transfer_guard before update or delete on examination_transfer
    for each row execute function examination_transfer_guard();

-- The licence. classes: [{classCode, classVersionId, issuedOn, status, automaticOnly, transferId}] (DL-021, DL-022);
-- conditions: [{code, note, since}] (DL-023).
create table licence (
    id                   uuid          primary key,
    licence_number       varchar(20)   not null unique references licence_number (licence_number),
    traffic_file_number  varchar(60)   not null,
    region               varchar(40)   not null,
    customer_category    varchar(40)   not null,
    status               varchar(12)   not null,
    issued_on            date          not null,
    expires_on           date          not null,
    probation_until      date,
    residency_expires_on date,
    suspended_until      date,
    classes              jsonb         not null,
    conditions           jsonb         not null,
    version              bigint        not null,
    created_at           timestamptz   not null,
    updated_at           timestamptz   not null,
    constraint ck_licence_status check (status in ('ACTIVE', 'SUSPENDED', 'CANCELLED', 'SURRENDERED')),
    constraint ck_licence_dates check (expires_on > issued_on),
    constraint ck_licence_classes check (jsonb_typeof(classes) = 'array' and jsonb_array_length(classes) >= 1),
    constraint ck_licence_conditions check (jsonb_typeof(conditions) = 'array')
);

create index ix_licence_holder on licence (traffic_file_number, status);

-- A licence keeps its number, holder and issue; a cancelled or surrendered licence never changes; never deleted.
create function licence_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'licences are never deleted (DL-021)';
    end if;
    if new.licence_number <> old.licence_number or new.traffic_file_number <> old.traffic_file_number
       or new.created_at <> old.created_at then
        raise exception 'a licence keeps its number and holder (DL-021)';
    end if;
    if old.status in ('CANCELLED', 'SURRENDERED') then
        raise exception 'a cancelled or surrendered licence never changes (DL-058)';
    end if;
    if new.version <> old.version + 1 then
        raise exception 'licence version must move by one (DAT-002)';
    end if;
    return new;
end
$$;

create trigger trg_licence_guard before update or delete on licence
    for each row execute function licence_guard();

-- What happened to a licence, in order; append only.
create table licence_history (
    id          bigserial     primary key,
    licence_id  uuid          not null references licence (id),
    event       varchar(60)   not null,
    actor_id    varchar(100)  not null,
    detail      jsonb         not null,
    at          timestamptz   not null
);

create index ix_licence_history on licence_history (licence_id, id);

create function licence_history_guard() returns trigger language plpgsql as $$
begin
    raise exception 'licence history is never changed (CC-120)';
end
$$;

create trigger trg_licence_history_guard before update or delete on licence_history
    for each row execute function licence_history_guard();

-- A paid licensing transaction waiting for payment-service; completed once.
create table licence_request (
    id                  uuid          primary key,
    kind                varchar(30)   not null,
    status              varchar(20)   not null,
    licence_id          uuid,
    traffic_file_number varchar(60)   not null,
    region              varchar(40)   not null,
    quote_id            varchar(60),
    payable_id          uuid,
    amount_minor        bigint        not null,
    currency            varchar(3),
    payload             jsonb         not null,
    transfer_ids        jsonb         not null,
    requested_by        varchar(100)  not null,
    channel             varchar(20),
    requested_at        timestamptz   not null,
    completed_at        timestamptz,
    constraint ck_licence_request_status check (status in ('AWAITING_PAYMENT', 'COMPLETED')),
    constraint ck_licence_request_completed check ((status = 'COMPLETED') = (completed_at is not null))
);

create index ix_licence_request_holder on licence_request (traffic_file_number, kind, status);

create function licence_request_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'licensing requests are never deleted (CC-120)';
    end if;
    if old.status <> 'AWAITING_PAYMENT'
       or (to_jsonb(new) - array['status', 'completed_at', 'licence_id'])
          is distinct from (to_jsonb(old) - array['status', 'completed_at', 'licence_id']) then
        raise exception 'a licensing request is only completed, once (DL-050)';
    end if;
    return new;
end
$$;

create trigger trg_licence_request_guard before update or delete on licence_request
    for each row execute function licence_request_guard();

-- licensing-service schema, part 5: licence cards (DL-052, DL-060), eye test results (DL-007, DL-051) and automatic
-- renewal consents (DL-065).

create table licence_card (
    id            uuid          primary key,
    licence_id    uuid          not null references licence (id),
    serial        integer       not null,
    reason        varchar(20)   not null,
    delivery      varchar(10)   not null,
    address       varchar(300),
    status        varchar(20)   not null,
    tracking_ref  varchar(100),
    created_at    timestamptz   not null,
    status_at     timestamptz   not null,
    constraint uq_licence_card_serial unique (licence_id, serial),
    constraint ck_licence_card_delivery check (delivery in ('CENTRE', 'COURIER')),
    constraint ck_licence_card_address check (delivery <> 'COURIER' or address is not null),
    constraint ck_licence_card_status check (status in ('PENDING_PRINT', 'SENT_TO_PRINTER', 'PRINTED', 'DISPATCHED',
        'DELIVERED', 'COLLECTED', 'RETURNED', 'CANCELLED'))
);

create index ix_licence_card_licence on licence_card (licence_id, serial desc);
create index ix_licence_card_status on licence_card (status, delivery, created_at);

-- A card keeps what it is; only its status and tracking move; a cancelled card never changes; never deleted.
create function licence_card_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'licence cards are never deleted (DL-052)';
    end if;
    if old.status = 'CANCELLED'
       or (to_jsonb(new) - array['status', 'tracking_ref', 'status_at'])
          is distinct from (to_jsonb(old) - array['status', 'tracking_ref', 'status_at']) then
        raise exception 'a licence card only moves its status, and a cancelled card never changes (DL-052)';
    end if;
    return new;
end
$$;

create trigger trg_licence_card_guard before update or delete on licence_card
    for each row execute function licence_card_guard();

create table eye_test (
    id                  uuid          primary key,
    traffic_file_number varchar(60)   not null,
    centre_code         varchar(40)   not null,
    reference           varchar(100)  not null,
    tested_on           date          not null,
    result              varchar(4)    not null,
    corrective_lenses   boolean       not null,
    permitted_classes   jsonb         not null,
    received_at         timestamptz   not null,
    received_by         varchar(100)  not null,
    constraint uq_eye_test unique (centre_code, reference),
    constraint ck_eye_test_result check (result in ('PASS', 'FAIL'))
);

create index ix_eye_test_holder on eye_test (traffic_file_number, tested_on desc);

create function eye_test_guard() returns trigger language plpgsql as $$
begin
    raise exception 'an eye test result never changes (DL-007)';
end
$$;

create trigger trg_eye_test_guard before update or delete on eye_test
    for each row execute function eye_test_guard();

create table auto_renewal (
    licence_id          uuid          primary key references licence (id),
    payment_method_ref  varchar(100)  not null,
    status              varchar(10)   not null,
    consent_by          varchar(100)  not null,
    consent_at          timestamptz   not null,
    notice_for_expiry   date,
    notice_at           timestamptz,
    attempt_for_expiry  date,
    attempt_at          timestamptz,
    withdrawn_by        varchar(100),
    withdrawn_at        timestamptz,
    constraint ck_auto_renewal_status check (status in ('ACTIVE', 'WITHDRAWN')),
    constraint ck_auto_renewal_withdrawn check ((status = 'WITHDRAWN') = (withdrawn_at is not null))
);

create index ix_auto_renewal_active on auto_renewal (status);

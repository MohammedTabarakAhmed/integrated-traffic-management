-- licensing-service schema, part 7: exchange rules per country (DL-054), the master file of foreign licences and
-- temporary permits (DL-055, DL-063) and international driving permits (DL-056).

create table exchange_country_rule (
    id               uuid          primary key,
    country_code     varchar(3)    not null,
    mode             varchar(20)   not null,
    class_map        jsonb         not null,
    status           varchar(12)   not null,
    submitted_by     varchar(100)  not null,
    submitted_at     timestamptz   not null,
    submit_reason    varchar(500)  not null,
    decided_by       varchar(100),
    decided_at       timestamptz,
    decision_reason  varchar(500),
    constraint ck_country_rule_mode check (mode in ('DIRECT_EXCHANGE', 'TEST_REQUIRED')),
    constraint ck_country_rule_status check (status in ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    constraint ck_country_rule_decided check ((status = 'PENDING') = (decided_at is null)),
    constraint ck_country_rule_maker_checker check (status not in ('APPROVED', 'SUPERSEDED')
        or decided_by <> submitted_by)
);

-- At most one approved rule per country.
create unique index uq_country_rule_approved on exchange_country_rule (country_code) where status = 'APPROVED';

-- Decided once; an approved rule is only superseded; never deleted.
create function exchange_country_rule_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'exchange country rules are never deleted (DL-054)';
    end if;
    if (to_jsonb(new) - array['status', 'decided_by', 'decided_at', 'decision_reason'])
       is distinct from (to_jsonb(old) - array['status', 'decided_by', 'decided_at', 'decision_reason']) then
        raise exception 'an exchange country rule never changes after it is submitted (DL-054)';
    end if;
    if not ((old.status = 'PENDING' and new.status in ('APPROVED', 'REJECTED'))
            or (old.status = 'APPROVED' and new.status = 'SUPERSEDED'
                and new.decided_at = old.decided_at)) then
        raise exception 'an exchange country rule is decided once and an approved rule only superseded (DL-054)';
    end if;
    return new;
end
$$;

create trigger trg_exchange_country_rule_guard before update or delete on exchange_country_rule
    for each row execute function exchange_country_rule_guard();

create table foreign_licence (
    id                       uuid          primary key,
    traffic_file_number      varchar(60)   not null,
    country_code             varchar(3)    not null,
    foreign_number           varchar(60)   not null,
    classes                  jsonb         not null,
    issued_on                date,
    expires_on               date          not null,
    language                 varchar(3)    not null,
    document_id              varchar(60)   not null,
    translation_document_id  varchar(60),
    status                   varchar(15)   not null,
    licence_id               uuid          references licence (id),
    recorded_by              varchar(100)  not null,
    recorded_at              timestamptz   not null,
    constraint uq_foreign_licence unique (country_code, foreign_number),
    constraint ck_foreign_licence_status check (status in ('RECORDED', 'EXCHANGED', 'TEST_REQUIRED')),
    constraint ck_foreign_licence_exchanged check ((status = 'EXCHANGED') = (licence_id is not null))
);

create index ix_foreign_licence_holder on foreign_licence (traffic_file_number);
create index ix_foreign_licence_master on foreign_licence (country_code, expires_on);

create function foreign_licence_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'foreign licences are never deleted (DL-063)';
    end if;
    if old.status <> 'RECORDED' or (to_jsonb(new) - array['status', 'licence_id'])
       is distinct from (to_jsonb(old) - array['status', 'licence_id']) then
        raise exception 'a foreign licence only changes its status once (DL-063)';
    end if;
    return new;
end
$$;

create trigger trg_foreign_licence_guard before update or delete on foreign_licence
    for each row execute function foreign_licence_guard();

create table temporary_permit (
    id                   uuid          primary key,
    permit_number        varchar(20)   not null unique references licence_number (licence_number),
    kind                 varchar(15)   not null,
    traffic_file_number  varchar(60)   not null,
    region               varchar(40)   not null,
    licence_id           uuid          references licence (id),
    foreign_licence_id   uuid          references foreign_licence (id),
    classes              jsonb         not null,
    valid_from           date          not null,
    valid_until          date          not null,
    status               varchar(10)   not null,
    request_id           uuid          references licence_request (id),
    issued_at            timestamptz   not null,
    constraint ck_temporary_permit_kind check (kind in ('VISITOR', 'CARD_PENDING')),
    constraint ck_temporary_permit_subject check ((kind = 'VISITOR' and foreign_licence_id is not null)
        or (kind = 'CARD_PENDING' and licence_id is not null)),
    constraint ck_temporary_permit_dates check (valid_until >= valid_from),
    constraint ck_temporary_permit_status check (status in ('ACTIVE', 'CANCELLED'))
);

create index ix_temporary_permit_holder on temporary_permit (traffic_file_number);
create index ix_temporary_permit_master on temporary_permit (valid_until);

create function temporary_permit_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'temporary permits are never deleted (DL-063)';
    end if;
    if old.status <> 'ACTIVE' or new.status <> 'CANCELLED'
       or (to_jsonb(new) - 'status') is distinct from (to_jsonb(old) - 'status') then
        raise exception 'a temporary permit is only cancelled, once (DL-055)';
    end if;
    return new;
end
$$;

create trigger trg_temporary_permit_guard before update or delete on temporary_permit
    for each row execute function temporary_permit_guard();

create sequence international_permit_seq;

create table international_permit (
    id             uuid          primary key,
    permit_number  varchar(30)   not null unique,
    licence_id     uuid          not null references licence (id),
    issued_on      date          not null,
    valid_until    date          not null,
    printer        varchar(40)   not null,
    status         varchar(10)   not null,
    printed_by     varchar(100),
    printed_at     timestamptz,
    request_id     uuid          references licence_request (id),
    issued_at      timestamptz   not null,
    constraint ck_international_permit_status check (status in ('ISSUED', 'PRINTED', 'CANCELLED')),
    constraint ck_international_permit_dates check (valid_until >= issued_on)
);

create index ix_international_permit_licence on international_permit (licence_id);

create function international_permit_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'international permits are never deleted (DL-056)';
    end if;
    if old.status <> 'ISSUED' or (to_jsonb(new) - array['status', 'printed_by', 'printed_at'])
       is distinct from (to_jsonb(old) - array['status', 'printed_by', 'printed_at']) then
        raise exception 'an international permit is only printed or cancelled, once (DL-056)';
    end if;
    return new;
end
$$;

create trigger trg_international_permit_guard before update or delete on international_permit
    for each row execute function international_permit_guard();

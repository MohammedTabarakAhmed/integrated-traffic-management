-- traffic-file-service schema, part 1: traffic files of individuals (DL-001, DL-002, DL-005, DL-012; ADR-041). Owned
-- exclusively by this service (ADR-016). Companies (DL-003, DL-004), the ban list (DL-013) and merges (DL-009) follow.

-- File numbers come from one sequence; the format (prefix, width) is configuration (Q-022).
create sequence traffic_file_number_seq start with 1 increment by 1 no cycle;

create table traffic_file (
    id                    uuid           primary key,
    file_number           varchar(20)    not null unique,
    kind                  varchar(12)    not null,
    category              varchar(40)    not null,
    type                  varchar(40)    not null,
    status                varchar(10)    not null,
    region_opened         varchar(40)    not null,
    surviving_file_id     uuid,
    -- Identity (DL-001, DL-002). REGISTER: from the identity register, locked against staff edits; DOCUMENT: entered
    -- from a visitor's passport.
    identity_source       varchar(10)    not null,
    register_synced_at    timestamptz,
    national_id_no        varchar(40),
    passport_no           varchar(20),
    passport_country      char(3),
    given_nl              varchar(100),
    family_nl             varchar(100),
    given_en              varchar(100),
    family_en             varchar(100),
    nationality           char(3)        not null,
    birth_date            date           not null,
    gender                varchar(40)    not null,
    residency_status      varchar(40),
    residency_expiry      date,
    -- Profile (DL-005). Health-related fields (blood group, physical conditions) are classified secret.
    mobile                varchar(16),
    email                 varchar(320),
    address               varchar(500),
    blood_group           varchar(40),
    education_level       varchar(40),
    profession            varchar(40),
    languages             varchar(40)[]  not null default '{}',
    physical_conditions   varchar(40)[]  not null default '{}',
    religion              varchar(40),
    determination_status  varchar(40),
    opened_at             timestamptz    not null,
    opened_by             varchar(100)   not null,
    opened_by_username    varchar(200),
    opened_channel        varchar(20)    not null,
    changed_at            timestamptz    not null,
    changed_by            varchar(100)   not null,
    changed_by_username   varchar(200),
    changed_channel       varchar(20)    not null,
    version               bigint         not null,
    legacy_system         varchar(40),
    legacy_id             varchar(100),
    constraint ck_tf_kind check (kind in ('INDIVIDUAL', 'COMPANY')),
    constraint ck_tf_status check (status in ('ACTIVE', 'MERGED', 'CLOSED', 'DECEASED')),
    constraint ck_tf_merged check ((status = 'MERGED') = (surviving_file_id is not null)),
    constraint ck_tf_codes check (category ~ '^[A-Z0-9_]{1,40}$' and type ~ '^[A-Z0-9_]{1,40}$'
        and region_opened ~ '^[A-Z0-9_]{1,40}$' and gender ~ '^[A-Z0-9_]{1,40}$'
        and nationality ~ '^[A-Z]{3}$' and file_number ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'),
    constraint ck_tf_identity check (
        (identity_source = 'REGISTER' and national_id_no is not null and passport_no is null
            and register_synced_at is not null)
        or (identity_source = 'DOCUMENT' and national_id_no is null and passport_no ~ '^[A-Z0-9]{5,20}$'
            and passport_country ~ '^[A-Z]{3}$' and register_synced_at is null)),
    constraint ck_tf_name check ((given_nl is not null and family_nl is not null)
        or (given_en is not null and family_en is not null)),
    constraint ck_tf_residency check (residency_expiry is null or residency_status is not null),
    constraint ck_tf_channels check (
        opened_channel in ('PORTAL', 'APP', 'BACKOFFICE', 'PARTNER', 'KIOSK', 'CALLCENTRE', 'FIELD', 'ASSISTANT', 'AGENT')
        and changed_channel in ('PORTAL', 'APP', 'BACKOFFICE', 'PARTNER', 'KIOSK', 'CALLCENTRE', 'FIELD', 'ASSISTANT',
                                'AGENT')),
    constraint ck_tf_version check (version >= 1)
);

-- One file per identity document (DL-001; exact duplicates of DL-009). A merged-away file keeps its document.
create unique index uq_tf_national_id on traffic_file (national_id_no)
    where national_id_no is not null and status <> 'MERGED';
create unique index uq_tf_passport on traffic_file (passport_no, passport_country)
    where passport_no is not null and status <> 'MERGED';
create unique index uq_tf_legacy on traffic_file (legacy_system, legacy_id) where legacy_id is not null;

-- Identity, number, kind and opening never change through the API, the version moves by one, and nothing is deleted
-- (DL-002, §6.1 "the number never changes", CC-122).
create function traffic_file_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'traffic files are never deleted (DL-001)';
    end if;
    if new.version <> old.version + 1
       or new.id <> old.id or new.file_number <> old.file_number or new.kind <> old.kind
       or new.identity_source <> old.identity_source
       or new.national_id_no is distinct from old.national_id_no
       or new.passport_no is distinct from old.passport_no
       or new.passport_country is distinct from old.passport_country
       or new.opened_at <> old.opened_at or new.opened_by <> old.opened_by then
        raise exception 'a traffic file keeps its number, kind, identity document and opening (DL-002)';
    end if;
    if new.identity_source = 'REGISTER' and new.register_synced_at is not distinct from old.register_synced_at
       and (new.given_nl, new.family_nl, new.given_en, new.family_en, new.nationality, new.birth_date, new.gender,
            new.residency_status, new.residency_expiry)
           is distinct from
           (old.given_nl, old.family_nl, old.given_en, old.family_en, old.nationality, old.birth_date, old.gender,
            old.residency_status, old.residency_expiry) then
        raise exception 'register-sourced fields change only with a register sync (DL-002)';
    end if;
    return new;
end
$$;

create trigger trg_traffic_file_guard before update or delete on traffic_file
    for each row execute function traffic_file_guard();

-- Temporal history (DAT-002, CC-121): the full row image of every version, written by trigger in the same transaction.
create table traffic_file_history (
    history_id   bigserial    primary key,
    file_id      uuid         not null,
    version      bigint       not null,
    row_image    jsonb        not null,
    valid_from   timestamptz  not null,
    valid_to     timestamptz,
    changed_by   varchar(100) not null,
    constraint uq_tf_history_version unique (file_id, version)
);

create function traffic_file_history_capture() returns trigger language plpgsql as $$
begin
    update traffic_file_history set valid_to = new.changed_at where file_id = new.id and valid_to is null;
    insert into traffic_file_history (file_id, version, row_image, valid_from, changed_by)
    values (new.id, new.version, to_jsonb(new), new.changed_at, new.changed_by);
    return new;
end
$$;

create trigger trg_traffic_file_history after insert or update on traffic_file
    for each row execute function traffic_file_history_capture();

create function traffic_file_history_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' or old.valid_to is not null
       or (to_jsonb(new) - 'valid_to') is distinct from (to_jsonb(old) - 'valid_to') then
        raise exception 'traffic file history is append-only (DAT-002)';
    end if;
    return new;
end
$$;

create trigger trg_traffic_file_history_guard before update or delete on traffic_file_history
    for each row execute function traffic_file_history_guard();

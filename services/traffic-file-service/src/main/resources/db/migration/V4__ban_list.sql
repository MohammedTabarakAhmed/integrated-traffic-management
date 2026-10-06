-- traffic-file-service schema, part 4: the ban list (DL-013; ADR-043). A person barred from services, identified by an
-- identity document (they may have no traffic file), with reason, source and period. Lifted once with a reason;
-- expired after the end date (derived). Never changed otherwise and never deleted; every version is kept (DAT-002).
create table ban_entry (
    id                   uuid          primary key,
    document_type        varchar(12)   not null,
    national_id_no       varchar(40),
    passport_no          varchar(20),
    passport_country     char(3),
    file_number          varchar(20),
    reason               varchar(500)  not null,
    source               varchar(40)   not null,
    source_ref           varchar(100),
    start_date           date          not null,
    end_date             date,
    status               varchar(10)   not null,
    placed_at            timestamptz   not null,
    placed_by            varchar(100)  not null,
    placed_by_username   varchar(200),
    placed_channel       varchar(20)   not null,
    lifted_at            timestamptz,
    lifted_by            varchar(100),
    lifted_by_username   varchar(200),
    lifted_channel       varchar(20),
    lift_reason          varchar(500),
    version              bigint        not null,
    constraint ck_ban_document check (
        (document_type = 'NATIONAL_ID' and national_id_no is not null and passport_no is null and passport_country is null)
        or (document_type = 'PASSPORT' and national_id_no is null and passport_no ~ '^[A-Z0-9]{5,20}$'
            and passport_country ~ '^[A-Z]{3}$')),
    constraint ck_ban_codes check (source ~ '^[A-Z0-9_]{1,40}$'
        and (source_ref is null or source_ref ~ '^[A-Za-z0-9._:/-]{1,100}$')),
    constraint ck_ban_reason check (char_length(btrim(reason)) between 3 and 500),
    constraint ck_ban_dates check (end_date is null or end_date >= start_date),
    constraint ck_ban_status check (status in ('ACTIVE', 'LIFTED')),
    constraint ck_ban_lifted check ((status = 'LIFTED') = (lifted_at is not null and lifted_by is not null
        and lifted_channel is not null and lift_reason is not null and char_length(btrim(lift_reason)) >= 3)),
    constraint ck_ban_version check (version >= 1)
);

create index ix_ban_national_id on ban_entry (national_id_no) where national_id_no is not null;
create index ix_ban_passport on ban_entry (passport_no, passport_country) where passport_no is not null;
-- One entry per source reference of a source (an agency's order is entered once).
create unique index uq_ban_source_ref on ban_entry (source, source_ref) where source_ref is not null;

create function ban_entry_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'ban entries are never deleted; lift them (DL-013)';
    end if;
    if old.status <> 'ACTIVE' or new.status <> 'LIFTED' or new.version <> old.version + 1
       or (to_jsonb(new) - array['status', 'lifted_at', 'lifted_by', 'lifted_by_username', 'lifted_channel',
                                  'lift_reason', 'version'])
          is distinct from (to_jsonb(old) - array['status', 'lifted_at', 'lifted_by', 'lifted_by_username',
                                                   'lifted_channel', 'lift_reason', 'version']) then
        raise exception 'a ban entry can only be lifted once; its other fields never change (DL-013)';
    end if;
    return new;
end
$$;

create trigger trg_ban_entry_guard before update or delete on ban_entry
    for each row execute function ban_entry_guard();

create table ban_entry_history (
    history_id   bigserial    primary key,
    ban_id       uuid         not null,
    version      bigint       not null,
    row_image    jsonb        not null,
    valid_from   timestamptz  not null,
    valid_to     timestamptz,
    changed_by   varchar(100) not null,
    constraint uq_ban_history_version unique (ban_id, version)
);

create function ban_entry_history_capture() returns trigger language plpgsql as $$
declare
    changed_at timestamptz := coalesce(new.lifted_at, new.placed_at);
    changed_by varchar(100) := coalesce(new.lifted_by, new.placed_by);
begin
    update ban_entry_history set valid_to = changed_at where ban_id = new.id and valid_to is null;
    insert into ban_entry_history (ban_id, version, row_image, valid_from, changed_by)
    values (new.id, new.version, to_jsonb(new), changed_at, changed_by);
    return new;
end
$$;

create trigger trg_ban_entry_history after insert or update on ban_entry
    for each row execute function ban_entry_history_capture();

create function ban_entry_history_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' or old.valid_to is not null
       or (to_jsonb(new) - 'valid_to') is distinct from (to_jsonb(old) - 'valid_to') then
        raise exception 'ban history is append-only (DAT-002)';
    end if;
    return new;
end
$$;

create trigger trg_ban_entry_history_guard before update or delete on ban_entry_history
    for each row execute function ban_entry_history_guard();

-- licensing-service schema, part 1: licence class versions (DL-020, DL-062; BRD §6.2). Owned exclusively by this
-- service (ADR-016).

create table licence_class_version (
    id                    uuid           primary key,
    class_code            varchar(20)    not null,
    version_no            integer        not null,
    name_nl               varchar(200)   not null,
    name_en               varchar(200)   not null,
    vehicle_type_nl       varchar(200)   not null,
    vehicle_type_en       varchar(200)   not null,
    gearbox_recorded      boolean        not null,
    -- one object per customer category (null category = every other category): minAgeYears, maxAgeYears,
    -- requiredTests, minTrainingHours, medicalRequired, validityMonths, residencyLinked
    category_rules        jsonb          not null,
    effective_from        date           not null,
    effective_to          date,
    status                varchar(10)    not null,
    submitted_at          timestamptz    not null,
    submitted_by          varchar(100)   not null,
    submitted_by_username varchar(200),
    submitted_channel     varchar(20)    not null,
    submit_reason         varchar(500)   not null,
    decided_at            timestamptz,
    decided_by            varchar(100),
    decided_by_username   varchar(200),
    decided_channel       varchar(20),
    decision_reason       varchar(500),
    constraint uq_licence_class_version unique (class_code, version_no),
    constraint ck_licence_class_code check (class_code ~ '^[A-Z][A-Z0-9_]{0,19}$'),
    constraint ck_licence_class_rules check (jsonb_typeof(category_rules) = 'array'
        and jsonb_array_length(category_rules) between 1 and 50),
    constraint ck_licence_class_dates check (effective_to is null or effective_to >= effective_from),
    constraint ck_licence_class_status check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    constraint ck_licence_class_decided check ((status = 'PENDING') = (decided_at is null)),
    constraint ck_licence_class_maker_checker check (status <> 'APPROVED' or decided_by <> submitted_by)
);

create index ix_licence_class_version_code on licence_class_version (class_code, status);

-- Decided once; an approved version may later only end earlier; never deleted (a licence always points at the exact
-- class rules it was issued under).
create function licence_class_version_guard() returns trigger language plpgsql as $$
declare
    fixed text[] := array['status', 'decided_at', 'decided_by', 'decided_by_username', 'decided_channel',
                          'decision_reason', 'effective_to'];
begin
    if tg_op = 'DELETE' then
        raise exception 'licence class versions are never deleted (DL-020)';
    end if;
    if (to_jsonb(new) - fixed) is distinct from (to_jsonb(old) - fixed) then
        raise exception 'a licence class version never changes after it is submitted (DL-020)';
    end if;
    if old.status = 'PENDING' then
        if new.status = 'PENDING' or new.effective_to is distinct from old.effective_to then
            raise exception 'a pending licence class version is only approved or rejected (DL-020)';
        end if;
    elsif old.status = 'APPROVED' then
        if new.status <> 'APPROVED'
           or (to_jsonb(new) - 'effective_to') is distinct from (to_jsonb(old) - 'effective_to')
           or new.effective_to is null
           or (old.effective_to is not null and new.effective_to >= old.effective_to) then
            raise exception 'an approved licence class version may only end earlier (DL-020)';
        end if;
    else
        raise exception 'a rejected licence class version never changes (DL-020)';
    end if;
    return new;
end
$$;

create trigger trg_licence_class_version_guard before update or delete on licence_class_version
    for each row execute function licence_class_version_guard();

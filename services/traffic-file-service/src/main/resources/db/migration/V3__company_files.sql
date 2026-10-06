-- traffic-file-service schema, part 3: company files (DL-003, DL-004; ADR-042). A company's file is opened with its
-- commercial licence, read from the economic licensing authority of the issuing region (partner #9, ICD-04); the
-- licence details are locked like a person's register identity (DL-002 by analogy). Signatories and fleet managers are
-- individuals' traffic files, kept as arrays of file numbers so that every change is in the temporal history.

alter table traffic_file
    alter column nationality drop not null,
    alter column birth_date drop not null,
    alter column gender drop not null,
    add column licence_source        varchar(40),
    add column commercial_licence_no varchar(40),
    add column company_name_nl       varchar(200),
    add column company_name_en       varchar(200),
    add column activities            varchar(40)[]  not null default '{}',
    add column licence_expiry        date,
    add column signatories           varchar(20)[]  not null default '{}',
    add column fleet_managers        varchar(20)[]  not null default '{}';

alter table traffic_file drop constraint ck_tf_identity;
alter table traffic_file drop constraint ck_tf_name;
alter table traffic_file drop constraint ck_tf_codes;

alter table traffic_file
    add constraint ck_tf_codes check (category ~ '^[A-Z0-9_]{1,40}$' and type ~ '^[A-Z0-9_]{1,40}$'
        and region_opened ~ '^[A-Z0-9_]{1,40}$' and (gender is null or gender ~ '^[A-Z0-9_]{1,40}$')
        and (nationality is null or nationality ~ '^[A-Z]{3}$') and file_number ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'
        and (licence_source is null or licence_source ~ '^[A-Z0-9_]{1,40}$')),
    add constraint ck_tf_individual check (kind <> 'INDIVIDUAL' or (
        nationality is not null and birth_date is not null and gender is not null
        and ((given_nl is not null and family_nl is not null) or (given_en is not null and family_en is not null))
        and licence_source is null and commercial_licence_no is null
        and cardinality(signatories) = 0 and cardinality(fleet_managers) = 0
        and ((identity_source = 'REGISTER' and national_id_no is not null and passport_no is null
              and register_synced_at is not null)
          or (identity_source = 'DOCUMENT' and national_id_no is null and passport_no ~ '^[A-Z0-9]{5,20}$'
              and passport_country ~ '^[A-Z]{3}$' and register_synced_at is null)))),
    add constraint ck_tf_company check (kind <> 'COMPANY' or (
        identity_source = 'REGISTER' and register_synced_at is not null
        and licence_source is not null and commercial_licence_no ~ '^[A-Za-z0-9/-]{1,40}$'
        and (company_name_nl is not null or company_name_en is not null) and licence_expiry is not null
        and national_id_no is null and passport_no is null and nationality is null and birth_date is null
        and cardinality(signatories) between 1 and 20 and cardinality(fleet_managers) <= 20)),
    add constraint ck_tf_representatives check (
        array_to_string(signatories || fleet_managers, ',') ~ '^([A-Z0-9][A-Z0-9-]{0,19}(,[A-Z0-9][A-Z0-9-]{0,19})*)?$');

-- One file per commercial licence of an issuing authority (DL-003).
create unique index uq_tf_commercial_licence on traffic_file (licence_source, commercial_licence_no)
    where commercial_licence_no is not null and status <> 'MERGED';

-- The guard of V1, extended: the licence never changes, and the authority's fields change only with a sync.
create or replace function traffic_file_guard() returns trigger language plpgsql as $$
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
       or new.licence_source is distinct from old.licence_source
       or new.commercial_licence_no is distinct from old.commercial_licence_no
       or new.opened_at <> old.opened_at or new.opened_by <> old.opened_by then
        raise exception 'a traffic file keeps its number, kind, identity document and opening (DL-002)';
    end if;
    if new.identity_source = 'REGISTER' and new.register_synced_at is not distinct from old.register_synced_at
       and (new.given_nl, new.family_nl, new.given_en, new.family_en, new.nationality, new.birth_date, new.gender,
            new.residency_status, new.residency_expiry, new.company_name_nl, new.company_name_en, new.activities,
            new.licence_expiry)
           is distinct from
           (old.given_nl, old.family_nl, old.given_en, old.family_en, old.nationality, old.birth_date, old.gender,
            old.residency_status, old.residency_expiry, old.company_name_nl, old.company_name_en, old.activities,
            old.licence_expiry) then
        raise exception 'register-sourced fields change only with a register sync (DL-002, DL-003)';
    end if;
    return new;
end
$$;

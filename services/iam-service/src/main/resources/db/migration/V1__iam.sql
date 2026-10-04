-- iam-service schema (CC-001, CC-002, CC-005, DAT-002). Owned exclusively by this service (ADR-016).
-- Rules are enforced in the domain and again here (defence in depth).

-- Permission catalogue: permissions declared by service APIs (x-itms-permission). Loaded by migration (ADR-021).
create table permission (
    code           varchar(100) primary key,
    service        varchar(60)  not null,
    description_nl varchar(200) not null,
    description_en varchar(200) not null,
    created_at     timestamptz  not null default now(),
    created_by     varchar(100) not null default 'SYSTEM_MIGRATION',
    constraint ck_permission_code check (code ~ '^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*){1,4}$')
);

-- Roles: named permission sets administered at runtime (CC-005). Never deleted; inactive roles grant nothing.
create table role (
    code         varchar(60)   primary key,
    name_nl      varchar(200)  not null,
    name_en      varchar(200)  not null,
    description  varchar(1000),
    active       boolean       not null,
    permissions  varchar(100)[] not null default '{}',
    version      bigint        not null,
    created_at   timestamptz   not null,
    created_by   varchar(100)  not null,
    updated_at   timestamptz   not null,
    updated_by   varchar(100)  not null,
    constraint ck_role_code check (code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
    constraint ck_role_version check (version >= 1)
);

-- Every permission of a role must exist in the catalogue.
create function role_permissions_check() returns trigger language plpgsql as $$
declare
    unknown text;
begin
    select string_agg(p, ', ') into unknown
      from unnest(new.permissions) as p
     where not exists (select 1 from permission c where c.code = p);
    if unknown is not null then
        raise exception 'role % has permissions that are not in the catalogue: % (CC-005)', new.code, unknown;
    end if;
    return new;
end
$$;

create trigger trg_role_permissions_check
    before insert or update on role
    for each row execute function role_permissions_check();

-- Temporal history of role definitions (DAT-002, CC-121), written by trigger in the same transaction.
create table role_history (
    history_id   bigserial     primary key,
    role_code    varchar(60)   not null,
    version      bigint        not null,
    name_nl      varchar(200)  not null,
    name_en      varchar(200)  not null,
    description  varchar(1000),
    active       boolean       not null,
    permissions  varchar(100)[] not null,
    valid_from   timestamptz   not null,
    valid_to     timestamptz,
    changed_by   varchar(100)  not null,
    constraint uq_role_history_version unique (role_code, version)
);

create function role_history_capture() returns trigger language plpgsql as $$
begin
    update role_history set valid_to = new.updated_at where role_code = new.code and valid_to is null;
    insert into role_history (role_code, version, name_nl, name_en, description, active, permissions, valid_from,
                              changed_by)
    values (new.code, new.version, new.name_nl, new.name_en, new.description, new.active, new.permissions,
            new.updated_at, new.updated_by);
    return new;
end
$$;

create trigger trg_role_history
    after insert or update on role
    for each row execute function role_history_capture();

-- Role assignments (CC-001): subject + role + data scopes + validity. Never deleted; revoked once with a reason.
create table role_assignment (
    id                  uuid          primary key,
    subject_id          varchar(100)  not null,
    subject_username    varchar(200),
    role_code           varchar(60)   not null references role (code),
    regions             varchar(40)[] not null,
    centres             varchar(40)[] not null,
    modules             varchar(40)[] not null,
    valid_from          timestamptz   not null,
    valid_to            timestamptz,
    status              varchar(10)   not null,
    reason              varchar(500)  not null,
    assigned_by         varchar(100)  not null,
    assigned_by_username varchar(200),
    assigned_at         timestamptz   not null,
    revoked_by          varchar(100),
    revoked_by_username varchar(200),
    revoked_at          timestamptz,
    revocation_reason   varchar(500),
    constraint ck_assignment_status check (status in ('ACTIVE', 'REVOKED')),
    constraint ck_assignment_validity check (valid_to is null or valid_to > valid_from),
    -- Segregation of duties (CC-002): nobody assigns a role to themselves.
    constraint ck_assignment_sod check (assigned_by <> subject_id),
    constraint ck_assignment_revoked check (
        (status = 'REVOKED') = (revoked_at is not null and revoked_by is not null and revocation_reason is not null)),
    -- The BRD scopes every role to at least one region and centre (§3.3); modules likewise (CC-001).
    constraint ck_assignment_scopes check (
        cardinality(regions) between 1 and 100 and cardinality(centres) between 1 and 100
        and cardinality(modules) between 1 and 100)
);

create index ix_assignment_subject_active on role_assignment (subject_id) where status = 'ACTIVE';
create index ix_assignment_role on role_assignment (role_code, status);

-- Assignments are append-only except a single ACTIVE -> REVOKED transition that sets only the revocation fields.
create function role_assignment_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'role assignments cannot be deleted; revoke them instead (DAT-002)';
    end if;
    if old.status <> 'ACTIVE' or new.status <> 'REVOKED'
       or (to_jsonb(new) - array['status', 'revoked_by', 'revoked_by_username', 'revoked_at', 'revocation_reason'])
          is distinct from
          (to_jsonb(old) - array['status', 'revoked_by', 'revoked_by_username', 'revoked_at', 'revocation_reason']) then
        raise exception 'role assignments can only be revoked once; other changes are not allowed (DAT-002)';
    end if;
    return new;
end
$$;

create trigger trg_role_assignment_guard
    before update or delete on role_assignment
    for each row execute function role_assignment_guard();

create function iam_no_delete() returns trigger language plpgsql as $$
begin
    raise exception '% rows cannot be deleted (DAT-002)', tg_table_name;
end
$$;

create trigger trg_role_no_delete before delete on role for each row execute function iam_no_delete();
create trigger trg_role_history_no_delete before delete on role_history for each row execute function iam_no_delete();
create trigger trg_permission_no_delete before delete on permission for each row execute function iam_no_delete();

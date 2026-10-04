-- Slice 1.3: configurable conflicting-role pairs (CC-002) and leaver deactivation (SEC-007, leaver half).
-- Rules are enforced in the domain and again here (defence in depth).

-- ---------------------------------------------------------------- conflicting roles (CC-002)

-- Pairs of roles that one subject must not hold together. Which pairs apply is a business decision (Q-030); the list
-- starts empty. Stored in alphabetical order so (A, B) and (B, A) are the same pair. Never deleted: a pair that no
-- longer applies is retired once, with a reason.
create table role_conflict (
    id                  uuid          primary key,
    role_a              varchar(60)   not null references role (code),
    role_b              varchar(60)   not null references role (code),
    reason              varchar(500)  not null,
    status              varchar(10)   not null,
    created_by          varchar(100)  not null,
    created_by_username varchar(200),
    created_at          timestamptz   not null,
    retired_by          varchar(100),
    retired_by_username varchar(200),
    retired_at          timestamptz,
    retirement_reason   varchar(500),
    -- Also excludes a role conflicting with itself.
    constraint ck_role_conflict_order check (role_a < role_b),
    constraint ck_role_conflict_status check (status in ('ACTIVE', 'RETIRED')),
    constraint ck_role_conflict_retired check (
        (status = 'RETIRED') = (retired_at is not null and retired_by is not null and retirement_reason is not null))
);

-- One ACTIVE pair per two roles; a retired pair can be declared again later.
create unique index uq_role_conflict_active on role_conflict (role_a, role_b) where status = 'ACTIVE';

-- Pairs are append-only except a single ACTIVE -> RETIRED transition that sets only the retirement fields.
create function role_conflict_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'role conflicts cannot be deleted; retire them instead (DAT-002)';
    end if;
    if old.status <> 'ACTIVE' or new.status <> 'RETIRED'
       or (to_jsonb(new) - array['status', 'retired_by', 'retired_by_username', 'retired_at', 'retirement_reason'])
          is distinct from
          (to_jsonb(old) - array['status', 'retired_by', 'retired_by_username', 'retired_at', 'retirement_reason']) then
        raise exception 'role conflicts can only be retired once; other changes are not allowed (DAT-002)';
    end if;
    return new;
end
$$;

create trigger trg_role_conflict_guard
    before update or delete on role_conflict
    for each row execute function role_conflict_guard();

-- Nobody receives a role (assigned or delegated) that conflicts with one they hold: an ACTIVE assignment that has not
-- ended (it may start later).
create function role_assignment_conflict_check() returns trigger language plpgsql as $$
declare
    held varchar(60);
begin
    if new.status <> 'ACTIVE' then
        return new;
    end if;
    select a.role_code into held
      from role_assignment a
      join role_conflict c on c.status = 'ACTIVE'
                          and ((c.role_a = new.role_code and c.role_b = a.role_code)
                               or (c.role_b = new.role_code and c.role_a = a.role_code))
     where a.subject_id = new.subject_id
       and a.status = 'ACTIVE'
       and (a.valid_to is null or a.valid_to > now())
     limit 1;
    if held is not null then
        raise exception 'subject % holds role %, which conflicts with role % (CC-002)',
            new.subject_id, held, new.role_code;
    end if;
    return new;
end
$$;

create trigger trg_role_assignment_conflict_check
    before insert on role_assignment
    for each row execute function role_assignment_conflict_check();

-- ---------------------------------------------------------------- catalogue and roles

insert into permission (code, service, description_nl, description_en) values
    ('iam.subject.deactivate', 'iam-service', 'إلغاء جميع أدوار المستخدم المغادر',
                                              'Deactivate a leaver: revoke all of a subject''s roles');

update role
   set permissions = permissions || array['iam.subject.deactivate']::varchar[],
       version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
 where code = 'IT_ADMINISTRATOR';

-- Held by the directory feed's service account (SEC-007): it can see assignments and deactivate leavers, nothing else.
insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('DIRECTORY_FEED', 'تغذية الدليل', 'Directory feed',
     'Service account of the HR/directory feed: deactivates leavers the same day (SEC-007).', true,
     array['iam.subject.deactivate', 'iam.assignment.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION');

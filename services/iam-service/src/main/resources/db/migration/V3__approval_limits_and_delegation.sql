-- Slice 1.2: approval limits per role (CC-003) and time-boxed delegation (CC-004).
-- Rules are enforced in the domain and again here (defence in depth).

-- ---------------------------------------------------------------- approval limits (CC-003)

-- Current limits of each role. Values are configuration, never code (the project guide Â§1.4). A role's limits are replaced
-- as a whole; each replacement also creates a new role version, so stale edits are refused.
create table approval_limit (
    role_code        varchar(60)  not null references role (code),
    limit_type       varchar(40)  not null,
    currency         varchar(3)   not null,
    max_amount_minor bigint       not null,
    changed_at       timestamptz  not null,
    changed_by       varchar(100) not null,
    constraint pk_approval_limit primary key (role_code, limit_type, currency),
    constraint ck_approval_limit_type check (limit_type ~ '^[A-Z0-9_]{1,40}$'),
    constraint ck_approval_limit_currency check (currency ~ '^[A-Z]{3}$'),
    constraint ck_approval_limit_amount check (max_amount_minor >= 0)
);

-- Temporal history of limits (DAT-002, CC-121), written by trigger in the same transaction.
create table approval_limit_history (
    history_id       bigserial    primary key,
    role_code        varchar(60)  not null,
    limit_type       varchar(40)  not null,
    currency         varchar(3)   not null,
    max_amount_minor bigint       not null,
    valid_from       timestamptz  not null,
    valid_to         timestamptz,
    changed_by       varchar(100) not null,
    ended_by         varchar(100)
);

create index ix_approval_limit_history_role on approval_limit_history (role_code, limit_type, currency);

-- A removed limit is ended at the time and by the actor of the role version that removed it (the role row is
-- updated first in the same transaction).
create function approval_limit_history_capture() returns trigger language plpgsql as $$
declare
    ended_at timestamptz;
    ender    varchar(100);
begin
    if tg_op in ('UPDATE', 'DELETE') then
        if tg_op = 'UPDATE' then
            ended_at := new.changed_at;
            ender := new.changed_by;
        else
            select updated_at, updated_by into ended_at, ender from role where code = old.role_code;
        end if;
        update approval_limit_history set valid_to = ended_at, ended_by = ender
         where role_code = old.role_code and limit_type = old.limit_type and currency = old.currency
           and valid_to is null;
    end if;
    if tg_op in ('INSERT', 'UPDATE') then
        insert into approval_limit_history (role_code, limit_type, currency, max_amount_minor, valid_from, changed_by)
        values (new.role_code, new.limit_type, new.currency, new.max_amount_minor, new.changed_at, new.changed_by);
    end if;
    return null;
end
$$;

create trigger trg_approval_limit_history
    after insert or update or delete on approval_limit
    for each row execute function approval_limit_history_capture();

create trigger trg_approval_limit_history_no_delete
    before delete on approval_limit_history for each row execute function iam_no_delete();

-- ---------------------------------------------------------------- delegation (CC-004)

-- A delegation is a role assignment with source DELEGATION pointing at the DIRECT assignment it was delegated from.
-- The existing guard keeps both columns immutable (only revocation fields may change).
alter table role_assignment
    add column source         varchar(12) not null default 'DIRECT',
    add column delegated_from uuid references role_assignment (id);

alter table role_assignment
    add constraint ck_assignment_source check (source in ('DIRECT', 'DELEGATION')),
    add constraint ck_assignment_delegated_from check ((source = 'DELEGATION') = (delegated_from is not null)),
    -- Delegation is temporary by definition: it always has an end.
    add constraint ck_delegation_validity check (source <> 'DELEGATION' or valid_to is not null);

create index ix_assignment_delegated_from on role_assignment (delegated_from) where delegated_from is not null;

-- Delegation rules that need the source row: no re-delegation, same role and scopes, not to the holder, and not
-- beyond the source's own end.
create function role_assignment_delegation_check() returns trigger language plpgsql as $$
declare
    src role_assignment%rowtype;
begin
    if new.source <> 'DELEGATION' then
        return new;
    end if;
    select * into src from role_assignment where id = new.delegated_from;
    if src.source <> 'DIRECT' then
        raise exception 'a delegation cannot be delegated again (CC-004)';
    end if;
    if src.role_code <> new.role_code or src.regions <> new.regions or src.centres <> new.centres
       or src.modules <> new.modules then
        raise exception 'a delegation must have the role and scopes of its source (CC-004)';
    end if;
    if src.subject_id = new.subject_id then
        raise exception 'a delegation cannot go to the holder of its source (CC-004)';
    end if;
    if src.valid_to is not null and new.valid_to > src.valid_to then
        raise exception 'a delegation cannot end after its source (CC-004)';
    end if;
    return new;
end
$$;

create trigger trg_role_assignment_delegation_check
    before insert on role_assignment
    for each row execute function role_assignment_delegation_check();

-- ---------------------------------------------------------------- catalogue

insert into permission (code, service, description_nl, description_en) values
    ('iam.delegation.create', 'iam-service', 'ØªÙÙˆÙŠØ¶ Ø§Ù„Ø£Ø¯ÙˆØ§Ø± ÙˆØ¥Ù„ØºØ§Ø¡ Ø§Ù„ØªÙÙˆÙŠØ¶ Ù†ÙŠØ§Ø¨Ø© Ø¹Ù† Ø£ØµØ­Ø§Ø¨Ù‡Ø§',
                                             'Delegate roles and revoke delegations on behalf of their holders'),
    ('iam.delegation.read',   'iam-service', 'Ø¹Ø±Ø¶ Ø§Ù„ØªÙÙˆÙŠØ¶Ø§Øª',                      'Read delegations');

update role
   set permissions = permissions || array['iam.delegation.create', 'iam.delegation.read']::varchar[],
       version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
 where code = 'IT_ADMINISTRATOR';

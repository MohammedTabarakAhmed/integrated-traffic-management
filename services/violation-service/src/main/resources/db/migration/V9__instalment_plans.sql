-- VF-071, VF-073 (ADR-062): the Authority's own instalment plans. The violations stay OPEN; their payables are replaced
-- by one payable per monthly instalment. A violation is in at most one plan. Plans and instalments are never deleted;
-- only status, payment and closing fields change.

create sequence instalment_plan_seq;

create table instalment_plan (
    id          uuid         primary key,
    number      varchar(30)  not null unique,
    file_number varchar(60)  not null,
    region      varchar(40)  not null,
    currency    char(3)      not null,
    total_minor bigint       not null,
    months      integer      not null,
    grace_days  integer      not null,
    status      varchar(9)   not null,
    created_by  varchar(100) not null,
    created_at  timestamptz  not null,
    closed_at   timestamptz,
    constraint ck_plan_status check (status in ('ACTIVE', 'LATE', 'COMPLETED')),
    constraint ck_plan_closed check ((status = 'COMPLETED') = (closed_at is not null)),
    constraint ck_plan_terms check (total_minor > 0 and months between 1 and 60 and grace_days between 0 and 90)
);

create index ix_plan_file on instalment_plan (file_number, created_at desc);

create table instalment_plan_violation (
    plan_id          uuid        not null references instalment_plan (id),
    violation_id     uuid        not null references violation (id),
    violation_number varchar(30) not null references violation (number),
    primary key (plan_id, violation_id),
    constraint uq_plan_violation unique (violation_id)
);

create table instalment (
    plan_id      uuid        not null references instalment_plan (id),
    seq          integer     not null,
    due_date     date        not null,
    amount_minor bigint      not null,
    payable_id   uuid        not null,
    paid_at      timestamptz,
    primary key (plan_id, seq),
    constraint ck_instalment check (seq >= 1 and amount_minor > 0)
);

create index ix_instalment_unpaid on instalment (due_date) where paid_at is null;

create function instalment_plan_guard() returns trigger language plpgsql as $$
declare
    mutable text[] := array['status', 'closed_at'];
begin
    if tg_op = 'DELETE' then
        raise exception 'instalment plans are never deleted (VF-071)';
    end if;
    if (to_jsonb(new) - mutable) is distinct from (to_jsonb(old) - mutable) then
        raise exception 'an instalment plan''s terms never change (VF-071)';
    end if;
    if old.status = 'COMPLETED' and new.status <> 'ACTIVE' then
        raise exception 'a completed plan only reopens when a receipt is cancelled (FN-011)';
    end if;
    return new;
end
$$;

create trigger trg_instalment_plan_guard before update or delete on instalment_plan
    for each row execute function instalment_plan_guard();

create function instalment_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'instalments are never deleted (VF-071)';
    end if;
    if (to_jsonb(new) - 'paid_at') is distinct from (to_jsonb(old) - 'paid_at') then
        raise exception 'an instalment''s schedule never changes (VF-071)';
    end if;
    return new;
end
$$;

create trigger trg_instalment_guard before update or delete on instalment
    for each row execute function instalment_guard();

create function instalment_plan_violation_guard() returns trigger language plpgsql as $$
begin
    raise exception 'the violations of an instalment plan never change (VF-071)';
end
$$;

create trigger trg_instalment_plan_violation_guard before update or delete on instalment_plan_violation
    for each row execute function instalment_plan_violation_guard();

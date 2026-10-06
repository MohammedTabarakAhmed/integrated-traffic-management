-- rules-service schema, part 1: rule versions and the decision log (CC-050..CC-053; ADR-048). Owned exclusively by this
-- service (ADR-016).

create table rule_version (
    id                    uuid           primary key,
    rule_set_code         varchar(60)    not null,
    version_no            integer        not null,
    name_nl               varchar(200)   not null,
    name_en               varchar(200)   not null,
    region                varchar(40),
    decision_table        jsonb          not null,
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
    constraint uq_rule_version unique (rule_set_code, version_no),
    constraint ck_rule_codes check (rule_set_code ~ '^[A-Z][A-Z0-9_]{1,59}$'
        and (region is null or region ~ '^[A-Z0-9_]{1,40}$')),
    constraint ck_rule_dates check (effective_to is null or effective_to >= effective_from),
    constraint ck_rule_status check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    constraint ck_rule_decided check ((status = 'PENDING') = (decided_at is null)),
    constraint ck_rule_maker_checker check (status <> 'APPROVED' or decided_by <> submitted_by)
);

create index ix_rule_version_set on rule_version (rule_set_code, status);

-- Decided once; an approved version may later only end earlier; never deleted (CC-051, CC-053: a logged decision
-- always points at the exact rules it used).
create function rule_version_guard() returns trigger language plpgsql as $$
declare
    fixed text[] := array['status', 'decided_at', 'decided_by', 'decided_by_username', 'decided_channel',
                          'decision_reason', 'effective_to'];
begin
    if tg_op = 'DELETE' then
        raise exception 'rule versions are never deleted (CC-051)';
    end if;
    if (to_jsonb(new) - fixed) is distinct from (to_jsonb(old) - fixed) then
        raise exception 'a rule version never changes after it is submitted (CC-051)';
    end if;
    if old.status = 'PENDING' then
        if new.status = 'PENDING' or new.effective_to is distinct from old.effective_to then
            raise exception 'a pending rule version is only approved or rejected (CC-051)';
        end if;
    elsif old.status = 'APPROVED' then
        if new.status <> 'APPROVED'
           or (to_jsonb(new) - 'effective_to') is distinct from (to_jsonb(old) - 'effective_to')
           or new.effective_to is null
           or (old.effective_to is not null and new.effective_to >= old.effective_to) then
            raise exception 'an approved rule version may only end earlier (CC-051)';
        end if;
    else
        raise exception 'a rejected rule version never changes (CC-051)';
    end if;
    return new;
end
$$;

create trigger trg_rule_version_guard before update or delete on rule_version
    for each row execute function rule_version_guard();

-- Decision log (CC-053): append-only.
create table rule_decision (
    id              uuid          primary key,
    rule_set_code   varchar(60)   not null,
    version_id      uuid          not null references rule_version (id),
    version_no      integer       not null,
    region          varchar(40),
    decision_date   date          not null,
    inputs          jsonb         not null,
    matched_rules   integer[]     not null,
    outputs         jsonb         not null,
    reference       varchar(100),
    actor_id        varchar(100)  not null,
    channel         varchar(20)   not null,
    correlation_id  varchar(64),
    decided_at      timestamptz   not null
);

create index ix_rule_decision_set on rule_decision (rule_set_code, decided_at desc);
create index ix_rule_decision_reference on rule_decision (reference) where reference is not null;

create function rule_decision_guard() returns trigger language plpgsql as $$
begin
    raise exception 'the decision log is append-only (CC-053)';
end
$$;

create trigger trg_rule_decision_guard before update or delete on rule_decision
    for each row execute function rule_decision_guard();

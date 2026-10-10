-- VF-023 (ADR-058): UNPAID_FINES holds placed at hold-eligibility-service for violations unpaid after the configured
-- period. At most one active hold per violation; a hold is never deleted and only its lifting is recorded.
create table unpaid_hold (
    id               uuid          primary key,
    violation_id     uuid          not null references violation (id),
    violation_number varchar(30)   not null,
    subject_type     varchar(12)   not null,
    subject_id       varchar(60)   not null,
    hold_id          varchar(100)  not null,
    placed_at        timestamptz   not null,
    lifted_at        timestamptz,
    constraint ck_unpaid_hold_subject check (subject_type in ('TRAFFIC_FILE', 'VEHICLE')),
    constraint ck_unpaid_hold_lifted check (lifted_at is null or lifted_at >= placed_at)
);

create unique index uq_unpaid_hold_active on unpaid_hold (violation_id) where lifted_at is null;
create index ix_violation_open_recorded on violation (recorded_at) where status = 'OPEN';

create function unpaid_hold_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'unpaid holds are never deleted (VF-023)';
    end if;
    if old.lifted_at is not null or (to_jsonb(new) - 'lifted_at') is distinct from (to_jsonb(old) - 'lifted_at') then
        raise exception 'an unpaid hold only records its lifting, once (VF-023)';
    end if;
    return new;
end
$$;

create trigger trg_unpaid_hold_guard before update or delete on unpaid_hold
    for each row execute function unpaid_hold_guard();

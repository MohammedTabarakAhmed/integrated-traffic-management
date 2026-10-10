-- Increment 4: objection workflow and device quality intake (VF-010..VF-012, VF-050..VF-052).

create table violation_objection (
    id               uuid          primary key,
    violation_id     uuid          not null references violation (id),
    violation_number varchar(30)   not null references violation (number),
    submitted_by     varchar(100)  not null,
    submitted_at     timestamptz   not null,
    reason_code      varchar(40)   not null,
    explanation      varchar(2000) not null,
    evidence_ids     varchar(64)[] not null,
    status           varchar(16)   not null,
    decided_by       varchar(100),
    decided_at       timestamptz,
    decision         varchar(16),
    decision_reason  varchar(500),
    constraint ck_objection_status check (status in ('PENDING', 'ACCEPTED', 'REJECTED', 'COURT')),
    constraint ck_objection_decision check ((status = 'PENDING') = (decided_at is null)),
    constraint ck_objection_decision_value check ((status = 'PENDING') = (decision is null)),
    constraint uq_objection_violation unique (violation_id)
);

create index ix_objection_queue on violation_objection (submitted_at) where status = 'PENDING';

create function objection_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'objections are never deleted (VF-050)';
    end if;
    if old.status <> 'PENDING' or new.status not in ('ACCEPTED', 'REJECTED', 'COURT')
       or (to_jsonb(new) - array['status', 'decided_by', 'decided_at', 'decision', 'decision_reason'])
          is distinct from (to_jsonb(old) - array['status', 'decided_by', 'decided_at', 'decision', 'decision_reason']) then
        raise exception 'an objection is submitted once and decided once (VF-050, VF-051)';
    end if;
    return new;
end
$$;

create trigger trg_objection_guard before update or delete on violation_objection
    for each row execute function objection_guard();


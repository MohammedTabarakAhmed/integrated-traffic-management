-- VF-051: a reviewer may accept an objection by reducing the fine; the reduced fine is kept on the decision.
-- CC-003: the reduction is limited by the reviewer's FINE_REDUCTION_AMOUNT approval limit (checked in the service).

alter table violation_objection add column reduced_fine_minor bigint;

alter table violation_objection add constraint ck_objection_decision_known
    check (decision is null or decision in ('ACCEPT', 'REDUCE', 'REJECT', 'REFER_TO_COURT'));

alter table violation_objection add constraint ck_objection_reduction
    check ((decision is not distinct from 'REDUCE') = (reduced_fine_minor is not null)
           and (reduced_fine_minor is null or (reduced_fine_minor > 0 and status = 'ACCEPTED')));

create or replace function objection_guard() returns trigger language plpgsql as $$
declare
    decided text[] := array['status', 'decided_by', 'decided_at', 'decision', 'decision_reason', 'reduced_fine_minor'];
begin
    if tg_op = 'DELETE' then
        raise exception 'objections are never deleted (VF-050)';
    end if;
    if old.status <> 'PENDING' or new.status not in ('ACCEPTED', 'REJECTED', 'COURT')
       or (to_jsonb(new) - decided) is distinct from (to_jsonb(old) - decided) then
        raise exception 'an objection is submitted once and decided once (VF-050, VF-051)';
    end if;
    return new;
end
$$;

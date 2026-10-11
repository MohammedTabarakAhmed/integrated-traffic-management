-- FN-012: an approved online refund is submitted to the gateway, which answers with its own reference. The decision
-- (status, decider, time) and that reference are recorded together, once; everything else stays as requested.
create or replace function refund_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'refund requests are never deleted (FN-012)';
    end if;
    if old.status <> 'PENDING' or new.status not in ('APPROVED', 'REJECTED', 'SUBMITTED')
       or (new.remote_ref is not null and new.status <> 'SUBMITTED')
       or (to_jsonb(new) - array['status', 'decided_by', 'decided_at', 'remote_ref'])
          is distinct from (to_jsonb(old) - array['status', 'decided_by', 'decided_at', 'remote_ref']) then
        raise exception 'a refund decision is recorded once (FN-012)';
    end if;
    return new;
end
$$;

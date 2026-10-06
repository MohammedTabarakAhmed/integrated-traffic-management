-- audit-service schema, part 1: the central audit trail (CC-120..CC-122; ADR-053). Owned exclusively by this service
-- (ADR-016). Records are appended once in a SHA-256 hash chain; they are never updated, and deleted only by the
-- retention job past the configured period (BRD section 18.4: 1 year online + 7 years archive).

create table audit_record (
    seq            bigint        primary key,
    audit_id       uuid          not null unique,
    occurred_at    timestamptz   not null,
    service        varchar(60)   not null,
    action         varchar(80)   not null,
    entity_type    varchar(60)   not null,
    entity_id      varchar(200)  not null,
    actor_id       varchar(100)  not null,
    actor_username varchar(200),
    actor_roles    jsonb         not null,
    channel        varchar(20),
    ip_address     varchar(64),
    correlation_id varchar(64),
    old_value      text,
    new_value      text,
    reason         varchar(500),
    received_at    timestamptz   not null,
    prev_hash      char(64)      not null,
    hash           char(64)      not null
);

create index ix_audit_entity on audit_record (entity_type, entity_id, seq);
create index ix_audit_actor on audit_record (actor_id, seq);
create index ix_audit_service_action on audit_record (service, action, seq);
create index ix_audit_occurred on audit_record (occurred_at);

-- Immutable (CC-122). Deleting is allowed only to the retention job, which sets itms.audit_purge for its transaction,
-- and only for records past the retention period it passes in itms.audit_purge_before.
create function audit_record_guard() returns trigger language plpgsql as $$
begin
    if tg_op in ('UPDATE', 'TRUNCATE') then
        raise exception 'audit records never change (CC-122)';
    end if;
    if coalesce(current_setting('itms.audit_purge', true), '') <> 'on'
       or old.occurred_at >= cast(current_setting('itms.audit_purge_before', true) as timestamptz) then
        raise exception 'audit records are deleted only by the retention job, past retention (CC-122)';
    end if;
    return old;
end
$$;

create trigger trg_audit_record_guard before update or delete on audit_record
    for each row execute function audit_record_guard();

create trigger trg_audit_record_no_truncate before truncate on audit_record
    for each statement execute function audit_record_guard();

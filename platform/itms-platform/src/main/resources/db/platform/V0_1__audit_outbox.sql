-- Platform migration shipped by itms-platform; included by services via spring.flyway.locations=classpath:db/platform.
-- Append-only audit outbox (CC-120, CC-122, ADR-017). Records are relayed to audit-service later; the relay may only
-- set published_at once. Every other update and every delete is rejected by trigger.
create table audit_outbox (
    id             uuid         primary key,
    occurred_at    timestamptz  not null,
    service        varchar(60)  not null,
    action         varchar(80)  not null,
    entity_type    varchar(60)  not null,
    entity_id      varchar(200) not null,
    actor_id       varchar(100) not null,
    actor_username varchar(200),
    actor_roles    jsonb        not null default '[]'::jsonb,
    channel        varchar(20)  not null,
    ip_address     varchar(64),
    correlation_id varchar(64),
    old_value      jsonb,
    new_value      jsonb,
    reason         varchar(500),
    published_at   timestamptz
);

create index ix_audit_outbox_unpublished on audit_outbox (occurred_at) where published_at is null;
create index ix_audit_outbox_entity on audit_outbox (entity_type, entity_id, occurred_at);

create function audit_outbox_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'audit_outbox is append-only (CC-122)';
    end if;
    if old.published_at is not null
       or (to_jsonb(new) - 'published_at') is distinct from (to_jsonb(old) - 'published_at') then
        raise exception 'audit_outbox is append-only; only published_at may be set once (CC-122)';
    end if;
    return new;
end
$$;

create trigger trg_audit_outbox_guard
    before update or delete on audit_outbox
    for each row execute function audit_outbox_guard();

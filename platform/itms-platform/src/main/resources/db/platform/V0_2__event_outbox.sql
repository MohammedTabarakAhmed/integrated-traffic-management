-- Platform migration shipped by itms-platform (ADR-040). Domain events are written in the same transaction as the
-- business change (transactional outbox, ADR-002) and relayed to the message broker by OutboxRelay (INT-005).
-- Like audit_outbox, the table is append-only: the relay may only set published_at once.
create table event_outbox (
    id             uuid         primary key,
    occurred_at    timestamptz  not null,
    service        varchar(60)  not null,
    event_type     varchar(120) not null,
    subject        varchar(200) not null,
    data           jsonb        not null,
    actor_id       varchar(100),
    correlation_id varchar(64),
    published_at   timestamptz
);

create index ix_event_outbox_unpublished on event_outbox (occurred_at) where published_at is null;

create function event_outbox_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'event_outbox is append-only (ADR-040)';
    end if;
    if old.published_at is not null
       or (to_jsonb(new) - 'published_at') is distinct from (to_jsonb(old) - 'published_at') then
        raise exception 'event_outbox is append-only; only published_at may be set once (ADR-040)';
    end if;
    return new;
end
$$;

create trigger trg_event_outbox_guard
    before update or delete on event_outbox
    for each row execute function event_outbox_guard();

-- Idempotent consumers (ADR-002): a consumer records each event id it has handled, in the same transaction as its
-- own change, so a redelivered event is skipped.
create table processed_event (
    consumer     varchar(100) not null,
    event_id     varchar(100) not null,
    processed_at timestamptz  not null,
    primary key (consumer, event_id)
);

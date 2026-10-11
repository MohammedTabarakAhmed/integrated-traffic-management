-- RPT-020: the warehouse of domain events from all modules, in reporting-service's own database (one database per
-- service). Personal identifiers are pseudonymised before they are stored (RPT-022). Facts are history: never changed.

create table fact_event (
    event_id     varchar(100) primary key,
    type         varchar(120) not null,
    subject      varchar(200),
    region       varchar(40),
    channel      varchar(20),
    occurred_at  timestamptz  not null,
    data         jsonb        not null,
    received_at  timestamptz  not null default now()
);

create index ix_fact_type_time on fact_event (type, occurred_at);
create index ix_fact_region_time on fact_event (region, occurred_at);

create function fact_event_guard() returns trigger language plpgsql as $$
begin
    raise exception 'warehouse facts are history and never change (RPT-020)';
end
$$;

create trigger trg_fact_event_guard before update or delete on fact_event
    for each row execute function fact_event_guard();

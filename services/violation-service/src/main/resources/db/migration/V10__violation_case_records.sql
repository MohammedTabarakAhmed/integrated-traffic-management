-- VF-046, VF-047 (ADR-068): the driver's signed undertakings (pledges) and the administrative preservation orders
-- recorded on violations, with their references. Both are records only: never changed or deleted once written.

create table violation_pledge (
    id               uuid         primary key,
    violation_id     uuid         not null references violation (id),
    violation_number varchar(30)  not null references violation (number),
    signed_by        varchar(200) not null,
    signed_at        timestamptz  not null,
    note             varchar(500),
    document_ids     text[]       not null,
    recorded_by      varchar(100) not null,
    recorded_at      timestamptz  not null,
    constraint uq_pledge_violation unique (violation_id),
    constraint ck_pledge_signed check (btrim(signed_by) <> '')
);

create index ix_pledge_file on violation_pledge (signed_at);

create table violation_preservation_order (
    id               uuid         primary key,
    violation_id     uuid         not null references violation (id),
    violation_number varchar(30)  not null references violation (number),
    reference        varchar(60)  not null,
    source           varchar(200) not null,
    start_at         timestamptz  not null,
    end_at           timestamptz,
    note             varchar(500),
    recorded_by      varchar(100) not null,
    recorded_at      timestamptz  not null,
    constraint uq_preservation_reference unique (violation_id, reference),
    constraint ck_preservation_window check (end_at is null or end_at > start_at),
    constraint ck_preservation_source check (btrim(source) <> '')
);

create index ix_preservation_violation on violation_preservation_order (violation_number);

create function violation_pledge_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'a pledge is never deleted (VF-046)';
    end if;
    raise exception 'a pledge is never changed (VF-046)';
end
$$;

create trigger trg_violation_pledge_guard before update or delete on violation_pledge
    for each row execute function violation_pledge_guard();

create function preservation_order_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'a preservation order is never deleted (VF-047)';
    end if;
    raise exception 'a preservation order is never changed (VF-047)';
end
$$;

create trigger trg_preservation_order_guard before update or delete on violation_preservation_order
    for each row execute function preservation_order_guard();

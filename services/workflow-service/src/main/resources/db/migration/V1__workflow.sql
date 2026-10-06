-- workflow-service schema, part 1: workflow definitions, work items, their timeline and notes, and queue members
-- (CC-060..CC-065; ADR-049). Owned exclusively by this service (ADR-016).

create table workflow_definition (
    code              varchar(40)   not null,
    version_no        integer       not null,
    service_code      varchar(20)   not null,
    name_nl           varchar(200)  not null,
    name_en           varchar(200)  not null,
    first_step        varchar(40)   not null,
    steps             jsonb         not null,
    published_at      timestamptz   not null,
    published_by      varchar(100)  not null,
    published_by_username varchar(200),
    publish_reason    varchar(500)  not null,
    constraint pk_workflow_definition primary key (code, version_no),
    constraint ck_workflow_codes check (code ~ '^[A-Z][A-Z0-9_]{1,39}$' and service_code ~ '^[A-Z][A-Z0-9_]{1,19}$')
);

-- A published version is never changed or deleted: running items point at it (CC-060).
create function workflow_definition_guard() returns trigger language plpgsql as $$
begin
    raise exception 'workflow versions never change and are never deleted (CC-060)';
end
$$;

create trigger trg_workflow_definition_guard before update or delete on workflow_definition
    for each row execute function workflow_definition_guard();

create table work_item (
    id                 uuid          primary key,
    reference          varchar(60)   not null,
    service_code       varchar(20)   not null,
    workflow_code      varchar(40)   not null,
    definition_version integer       not null,
    region             varchar(40)   not null,
    centre             varchar(40),
    customer_id        varchar(100),
    step_code          varchar(40)   not null,
    queue_role         varchar(60)   not null,
    escalation_role    varchar(60)   not null,
    status             varchar(10)   not null,
    outcome            varchar(40),
    assignee           varchar(100),
    created_at         timestamptz   not null,
    step_started_at    timestamptz   not null,
    due_at             timestamptz   not null,
    paused_at          timestamptz,
    escalated_at       timestamptz,
    closed_at          timestamptz,
    version            bigint        not null default 0,
    constraint fk_work_item_definition foreign key (workflow_code, definition_version)
        references workflow_definition (code, version_no),
    constraint ck_work_item_status check (status in ('OPEN', 'RETURNED', 'COMPLETED', 'CANCELLED')),
    constraint ck_work_item_paused check ((status = 'RETURNED') = (paused_at is not null)),
    constraint ck_work_item_closed check ((status in ('COMPLETED', 'CANCELLED')) = (closed_at is not null)),
    constraint ck_work_item_outcome check ((status = 'COMPLETED') = (outcome is not null))
);

create index ix_work_item_assignee on work_item (assignee, due_at) where status in ('OPEN', 'RETURNED');
create index ix_work_item_queue on work_item (queue_role, due_at) where status in ('OPEN', 'RETURNED');
create index ix_work_item_escalated on work_item (escalation_role, due_at) where escalated_at is not null
    and status = 'OPEN';
create index ix_work_item_due on work_item (due_at) where status = 'OPEN' and escalated_at is null;
create unique index uq_work_item_active on work_item (reference, workflow_code) where status in ('OPEN', 'RETURNED');

-- Closed items never change; items are never deleted (CC-060, CC-122).
create function work_item_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'work items are never deleted (CC-060)';
    end if;
    if old.status in ('COMPLETED', 'CANCELLED') then
        raise exception 'a closed work item never changes (CC-060)';
    end if;
    if new.id <> old.id or new.reference <> old.reference or new.workflow_code <> old.workflow_code
       or new.definition_version <> old.definition_version or new.region <> old.region
       or new.centre is distinct from old.centre or new.customer_id is distinct from old.customer_id
       or new.created_at <> old.created_at then
        raise exception 'the identity of a work item never changes (CC-060)';
    end if;
    return new;
end
$$;

create trigger trg_work_item_guard before update or delete on work_item
    for each row execute function work_item_guard();

-- Timeline of a case (CC-121): append-only.
create table work_item_history (
    id        bigint generated always as identity primary key,
    item_id   uuid          not null references work_item (id),
    action    varchar(40)   not null,
    step_code varchar(40)   not null,
    actor_id  varchar(100)  not null,
    at        timestamptz   not null,
    detail    jsonb         not null
);

create index ix_work_item_history on work_item_history (item_id, id);

-- Internal notes (CC-064) and customer responses (CC-065): append-only. visible_to_customer is always false for
-- notes; the column documents the rule for later customer-facing views.
create table work_item_note (
    id                  uuid          primary key,
    item_id             uuid          not null references work_item (id),
    text                varchar(4000) not null,
    attachment_ids      varchar(64)[] not null,
    author_id           varchar(100)  not null,
    author_username     varchar(200),
    at                  timestamptz   not null,
    visible_to_customer boolean       not null default false,
    constraint ck_note_internal check (not visible_to_customer)
);

create index ix_work_item_note on work_item_note (item_id, at);

create function workflow_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'this table is append-only (CC-064, CC-121)';
end
$$;

create trigger trg_work_item_history_guard before update or delete on work_item_history
    for each row execute function workflow_append_only();
create trigger trg_work_item_note_guard before update or delete on work_item_note
    for each row execute function workflow_append_only();

-- Users available for automatic assignment per role (CC-061); scopes copied from their own grants when they join.
create table queue_member (
    user_id          varchar(100)  not null,
    username         varchar(200),
    role             varchar(60)   not null,
    regions          varchar(40)[] not null,
    centres          varchar(40)[] not null,
    available        boolean       not null,
    last_assigned_at timestamptz,
    updated_at       timestamptz   not null,
    constraint pk_queue_member primary key (user_id, role)
);

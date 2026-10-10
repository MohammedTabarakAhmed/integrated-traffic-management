-- Violations that cross a border (Increment 9): recorded here against a vehicle registered in a partner state
-- (OUTBOUND, XB-001) or loaded from a partner state's message (INBOUND, XB-002). Source PARTNER marks loaded ones.

create table violation_crossborder (
    violation_id  uuid          primary key references violation (id),
    partner_code  varchar(12)   not null,
    direction     varchar(8)    not null,
    external_id   varchar(120),
    recorded_at   timestamptz   not null,
    constraint ck_violation_crossborder_partner check (partner_code ~ '^[A-Z0-9]{2,12}$'),
    constraint ck_violation_crossborder_direction check (direction in ('INBOUND', 'OUTBOUND')),
    constraint ck_violation_crossborder_external check ((direction = 'INBOUND') = (external_id is not null)),
    constraint uq_violation_crossborder_external unique (partner_code, external_id)
);

create function violation_crossborder_guard() returns trigger language plpgsql as $$
begin
    raise exception 'cross-border links are never changed or deleted (XB-007)';
end
$$;

create trigger trg_violation_crossborder_guard before update or delete on violation_crossborder
    for each row execute function violation_crossborder_guard();

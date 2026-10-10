-- Pause payable collection while a customer objection is under review (VF-052).

create table payable_hold (
    id          uuid          primary key,
    payable_id  uuid          not null references payable (id),
    source_ref  varchar(80)   not null,
    reason      varchar(500)  not null,
    active      boolean       not null,
    held_by     varchar(100)  not null,
    held_at     timestamptz   not null,
    lift_reason varchar(500),
    lifted_by   varchar(100),
    lifted_at   timestamptz,
    constraint uq_payable_hold_source unique (payable_id, source_ref),
    constraint ck_payable_hold_lift check ((active and lifted_at is null and lifted_by is null)
        or (not active and lifted_at is not null and lifted_by is not null and lift_reason is not null))
);

create index ix_payable_active_holds on payable_hold (payable_id) where active;

-- Investigator decisions are retained as a separate history (AC-005, AC-009, AC-011).
create table accident_decision (
    id bigint generated always as identity primary key,
    accident_id uuid not null references accident_report(id),
    decision_type varchar(24) not null,
    reason varchar(500) not null,
    decided_by varchar(100) not null,
    decided_at timestamptz not null,
    constraint ck_accident_decision_type check (decision_type in ('FAULT','OBJECTION','PROSECUTION','CLOSE'))
);
create index ix_accident_decision_history on accident_decision(accident_id,decided_at);

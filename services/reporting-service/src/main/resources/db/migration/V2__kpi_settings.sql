-- RPT-012: KPI targets and red/amber/green thresholds that business users set (reporting-service, ADR-069).
-- One row per KPI, authority-wide; setting replaces the row in place (the change is audited, so the history
-- stays in the audit record). No values are stored here -- the thresholds judge actual values from the warehouse
-- (or any source) at read time; nothing downstream blocks or changes.

create table report_kpi_setting (
    code             varchar(40) primary key,
    target           bigint      not null,
    red_threshold    bigint      not null,
    amber_threshold  bigint      not null,
    higher_is_better boolean     not null default true,
    updated_by       varchar(64) not null,
    updated_at       timestamptz not null
);

comment on table report_kpi_setting is 'KPI targets and RAG thresholds set by business users (RPT-012, ADR-069)';

comment on column report_kpi_setting.target is 'the goal the KPI aims at, on the KPI''s own scale';
comment on column report_kpi_setting.red_threshold is 'RED at or below this value when the KPI counts towards the goal, at or above when it counts against it';
comment on column report_kpi_setting.amber_threshold is 'AMBER boundary between red and green';
comment on column report_kpi_setting.higher_is_better is 'true when a higher actual value is closer to the goal';

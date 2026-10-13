-- Legacy systems (DAT-001)
create table legacy_system (
    id uuid primary key,
    code varchar(40) not null unique,
    name_en varchar(100) not null,
    name_ar varchar(100) not null,
    description_en varchar(500),
    description_ar varchar(500),
    connection_config text not null,
    entity_mappings text,
    active boolean not null default true,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

-- Entity mappings (DAT-002)
create table entity_mapping (
    id uuid primary key,
    legacy_system_code varchar(40) not null references legacy_system(code),
    legacy_entity_name varchar(100) not null,
    target_entity_name varchar(100) not null,
    field_mappings jsonb not null,
    transformation_rules jsonb,
    validation_rules jsonb,
    active boolean not null default true,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (legacy_system_code, legacy_entity_name)
);
create index entity_mapping_system on entity_mapping(legacy_system_code);

-- Migration jobs (DAT-010)
create table migration_job (
    id uuid primary key,
    code varchar(40) not null unique,
    name_en varchar(100) not null,
    name_ar varchar(100) not null,
    legacy_system_code varchar(40) not null references legacy_system(code),
    entity_mappings text not null,
    status varchar(20) not null check (status in ('SCHEDULED','RUNNING','COMPLETED','FAILED','PARTIAL','CANCELLED','ROLLED_BACK')),
    scheduled_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    total_records bigint not null default 0,
    processed_records bigint not null default 0,
    successful_records bigint not null default 0,
    failed_records bigint not null default 0,
    error_summary text,
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create index migration_job_system on migration_job(legacy_system_code);

-- Migration executions (per entity within a job)
create table migration_execution (
    id uuid primary key,
    job_id uuid not null references migration_job(id),
    entity_name varchar(100) not null,
    status varchar(20) not null check (status in ('PENDING','RUNNING','COMPLETED','FAILED')),
    total_records bigint not null default 0,
    processed_records bigint not null default 0,
    successful_records bigint not null default 0,
    failed_records bigint not null default 0,
    started_at timestamptz,
    completed_at timestamptz,
    error_details text,
    unique (job_id, entity_name)
);
create index migration_exec_job on migration_execution(job_id);

-- Reconciliation reports (DAT-010)
create table reconciliation_report (
    id uuid primary key,
    job_id uuid not null references migration_job(id),
    entity_name varchar(100) not null,
    source_count bigint not null,
    target_count bigint not null,
    matched_count bigint not null,
    mismatched_count bigint not null,
    missing_in_target bigint not null,
    extra_in_target bigint not null,
    match_percentage double precision not null,
    financial_reconciliation jsonb not null default '{}',
    generated_at timestamptz not null,
    status varchar(20) not null check (status in ('PASSED','FAILED','WARNING','PENDING_REVIEW'))
);
create index recon_report_job on reconciliation_report(job_id);
create index recon_report_entity on reconciliation_report(job_id, entity_name);

-- Exception store (DAT-011)
create table exception_record (
    id uuid primary key,
    job_id uuid not null references migration_job(id),
    execution_id uuid not null references migration_execution(id),
    entity_name varchar(100) not null,
    legacy_id varchar(120) not null,
    legacy_data jsonb not null,
    target_id varchar(120),
    error_code varchar(40) not null,
    error_message text not null,
    severity varchar(10) not null check (severity in ('LOW','MEDIUM','HIGH','CRITICAL')),
    assigned_to varchar(120),
    resolution text,
    created_at timestamptz not null,
    resolved_at timestamptz
);
create index exception_job on exception_record(job_id);
create index exception_unresolved on exception_record(job_id, resolved_at) where resolved_at is null;
create index exception_severity on exception_record(severity, created_at desc);

-- Rollback plans (DAT-013)
create table rollback_plan (
    id uuid primary key,
    job_code varchar(40) not null unique references migration_job(code),
    name_en varchar(100) not null,
    name_ar varchar(100) not null,
    description_en varchar(500),
    description_ar varchar(500),
    rollback_steps jsonb not null,
    validation_queries jsonb not null,
    tested boolean not null default false,
    tested_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

-- Anonymization rules (DAT-014)
create table anonymization_rule (
    id uuid primary key,
    entity_name varchar(100) not null,
    field_name varchar(100) not null,
    rule_type varchar(40) not null,
    rule_config jsonb not null,
    active boolean not null default true,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (entity_name, field_name)
);
create index anon_rule_entity on anonymization_rule(entity_name);

-- Seed example legacy systems
insert into legacy_system (id, code, name_en, name_ar, description_en, description_ar, connection_config, entity_mappings, active, created_at, updated_at) values
(gen_random_uuid(), 'LEGACY_TRAFFIC', 'Legacy Traffic System', 'نظام المرور القديم', 'Mainframe traffic file system from 1990s', 'نظام ملفات المرور من التسعينيات', '{"type":"jdbc","url":"jdbc:oracle:thin:@legacy:1521:TRAFFIC","schema":"TRAFFIC"}', 'TRAFFIC_FILE,VEHICLE,LICENCE,VIOLATION', true, now(), now()),
(gen_random_uuid(), 'LEGACY_FINANCE', 'Legacy Finance System', 'نظام المالية القديم', 'Old revenue collection and accounting system', 'نظام تحصيل الإيرادات والمحاسبة القديم', '{"type":"jdbc","url":"jdbc:db2://legacy:50000/FINANCE","schema":"FINANCE"}', 'PAYMENT,RECEIPT,REFUND,DEPOSIT', true, now(), now()),
(gen_random_uuid(), 'LEGACY_COURT', 'Legacy Court System', 'نظام المحاكم القديم', 'Violation court case management system', 'نظام إدارة قضايا المخالفات في المحاكم', '{"type":"soap","endpoint":"https://legacy-court.gov/ws/cases"}', 'COURT_CASE,JUDGEMENT,INSTALMENT', true, now(), now());
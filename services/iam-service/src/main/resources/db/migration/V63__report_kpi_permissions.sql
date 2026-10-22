-- RPT-012: KPI targets and red/amber/green thresholds that business users set (reporting-service, ADR-069).
-- A dedicated manage role, not an extra grant on REPORT_VIEWER: a report viewer may read the settings but never
-- change them. report.read keeps its region scoping from iam V56; the KPI settings themselves are authority-wide.
-- Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('report.kpi.manage', 'reporting-service', 'ضبط أهداف ومستويات مؤشرات الأداء',
     'Set KPI targets and red/amber/green thresholds');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('REPORT_MANAGER', 'مدير التقارير', 'Report manager',
     'Sets KPI targets and red/amber/green thresholds (RPT-012) and reads the reports like a viewer.', true,
     array['report.read', 'report.kpi.manage'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

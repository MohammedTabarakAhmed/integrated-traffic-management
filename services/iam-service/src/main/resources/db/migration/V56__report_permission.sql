-- RPT-001, RPT-004 (Increment 11): reports over the pseudonymised warehouse (RPT-022). report.read is scoped by the
-- assignment's regions: a regional analyst sees only their regions' rows. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('report.read', 'reporting-service', 'عرض التقارير وتصديرها',
     'Run and export reports within the assignment''s data scope');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('REPORT_VIEWER', 'مستعرض التقارير', 'Report viewer',
     'Runs and exports reports for the regions of the assignment (RPT-001, RPT-004).', true,
     array['report.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

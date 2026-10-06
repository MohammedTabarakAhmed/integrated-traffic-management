-- Merging traffic files (DL-009; ADR-044): a supervisor's decision, kept apart from the officer role. Any grant.
-- Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('trafficfile.merge', 'traffic-file-service', 'دمج الملفات المرورية المكررة', 'Merge duplicate traffic files');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('TRAFFIC_FILE_SUPERVISOR', 'مشرف الملفات المرورية', 'Traffic file supervisor',
     'Reviews probable duplicate traffic files and merges them (DL-009).',
     true, array['trafficfile.read', 'trafficfile.merge'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

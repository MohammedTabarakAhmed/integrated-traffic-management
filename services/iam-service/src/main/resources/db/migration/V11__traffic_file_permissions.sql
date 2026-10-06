-- Traffic-file permissions of traffic-file-service (DL-001, DL-002, DL-005; ADR-041) and the staff roles that use
-- them. Opening is scoped by the region the file is opened in; reading and updating apply in any grant, because a
-- customer may use services in any region (CC-113, Q-057). Seeing full identity numbers and health-related fields
-- (classified secret, SEC-014) is a separate permission. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('trafficfile.create',         'traffic-file-service', 'فتح الملفات المرورية',                  'Open traffic files'),
    ('trafficfile.read',           'traffic-file-service', 'عرض الملفات المرورية وسجل إصداراتها',   'Read traffic files and their version history'),
    ('trafficfile.update',         'traffic-file-service', 'تعديل بيانات الملفات المرورية',          'Update traffic file profiles'),
    ('trafficfile.sensitive.read', 'traffic-file-service', 'عرض أرقام الهوية والبيانات الصحية كاملة', 'See full identity numbers and health-related fields');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('TRAFFIC_FILE_OFFICER', 'موظف الملفات المرورية', 'Traffic file officer',
     'Opens traffic files in their regions, reads them and updates their profiles (DL-001, DL-005).',
     true, array['trafficfile.create', 'trafficfile.read', 'trafficfile.update'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('TRAFFIC_FILE_SENSITIVE_READER', 'مطّلع على البيانات الحساسة للملفات المرورية', 'Traffic file sensitive-data reader',
     'Sees full identity numbers and health-related fields of traffic files (SEC-014); granted only where needed.',
     true, array['trafficfile.read', 'trafficfile.sensitive.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION');

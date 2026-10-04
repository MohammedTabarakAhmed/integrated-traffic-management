-- Initial load (ADR-021): the permission catalogue of the services built so far and the starting roles.
-- Arabic texts are DRAFT (Q-006): to be replaced by Authority-approved wording through the normal admin APIs.
-- tools/ci/check_permissions.py fails the build if a service API declares a permission missing from this catalogue.

insert into permission (code, service, description_nl, description_en) values
    ('iam.permission.read',   'iam-service', 'عرض كتالوج الصلاحيات',               'Read the permission catalogue'),
    ('iam.role.read',         'iam-service', 'عرض الأدوار',                        'Read roles'),
    ('iam.role.manage',       'iam-service', 'إنشاء الأدوار وتعديل صلاحياتها',      'Create roles and change their permissions'),
    ('iam.history.read',      'iam-service', 'عرض سجل تغييرات الأدوار',            'Read role history'),
    ('iam.assignment.read',   'iam-service', 'عرض إسناد الأدوار',                  'Read role assignments'),
    ('iam.assignment.manage', 'iam-service', 'إسناد الأدوار وإلغاؤها',             'Assign and revoke roles'),
    ('refdata.read',           'reference-data-service', 'عرض البيانات المرجعية',          'Read reference data'),
    ('refdata.history.read',   'reference-data-service', 'عرض سجل البيانات المرجعية',      'Read reference item history'),
    ('refdata.change.submit',  'reference-data-service', 'تقديم تغييرات البيانات المرجعية', 'Submit reference data changes'),
    ('refdata.change.read',    'reference-data-service', 'عرض تغييرات البيانات المرجعية',   'Read reference data changes'),
    ('refdata.change.approve', 'reference-data-service', 'اعتماد تغييرات البيانات المرجعية أو رفضها',
                                                                                          'Approve or reject reference data changes');

-- Starting roles. IT_ADMINISTRATOR follows BRD §3.3 ("manages users, roles, integrations and system configuration").
-- REFDATA_MAKER / REFDATA_CHECKER replace the interim Keycloak composite roles of ADR-018.
insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('IT_ADMINISTRATOR', 'مسؤول تقنية المعلومات', 'IT administrator',
     'Manages users and roles (BRD §3.3).', true,
     array['iam.permission.read', 'iam.role.read', 'iam.role.manage', 'iam.history.read', 'iam.assignment.read',
           'iam.assignment.manage'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('REFDATA_MAKER', 'معدّ البيانات المرجعية', 'Reference data maker',
     'Prepares reference data changes (CC-114 maker).', true,
     array['refdata.read', 'refdata.change.submit', 'refdata.change.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('REFDATA_CHECKER', 'مدقق البيانات المرجعية', 'Reference data checker',
     'Approves or rejects reference data changes (CC-114 checker).', true,
     array['refdata.read', 'refdata.change.read', 'refdata.change.approve', 'refdata.history.read'], 1, now(),
     'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

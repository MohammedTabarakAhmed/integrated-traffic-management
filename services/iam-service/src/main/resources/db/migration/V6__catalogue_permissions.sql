-- Service catalogue permissions of transaction-service (CC-020, CC-026, CC-114; ADR-028) and its maker and checker
-- roles. Reading the catalogue needs only a signed-in user (x-itms-permission: authenticated), so it has no permission.
-- Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('catalogue.history.read',   'transaction-service', 'عرض سجل تعريفات الخدمات',             'Read service definition history'),
    ('catalogue.change.submit',  'transaction-service', 'تقديم تغييرات كتالوج الخدمات',         'Submit service catalogue changes'),
    ('catalogue.change.read',    'transaction-service', 'عرض تغييرات كتالوج الخدمات',           'Read service catalogue changes'),
    ('catalogue.change.approve', 'transaction-service', 'اعتماد تغييرات كتالوج الخدمات أو رفضها',
                                                                                         'Approve or reject service catalogue changes');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('CATALOGUE_MAKER', 'معدّ كتالوج الخدمات', 'Service catalogue maker',
     'Prepares service catalogue changes: definitions and availability per region and channel (CC-020, CC-026; CC-114 maker).',
     true, array['catalogue.change.submit', 'catalogue.change.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('CATALOGUE_CHECKER', 'مدقق كتالوج الخدمات', 'Service catalogue checker',
     'Approves or rejects service catalogue changes (CC-114 checker).', true,
     array['catalogue.change.read', 'catalogue.change.approve', 'catalogue.history.read'], 1, now(),
     'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

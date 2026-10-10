-- Licence transaction permissions of licensing-service (DL-007, DL-051, DL-052, DL-057, DL-065; Increment 3 slice
-- 3.4). Officers renew, replace and change licences; optometry centres send eye test results with their own account.
-- Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('licensing.licence.renew',        'licensing-service', 'طلب تجديد رخصة القيادة',          'Request a driving licence renewal'),
    ('licensing.licence.replace',      'licensing-service', 'طلب بدل فاقد أو تالف للرخصة',      'Request a lost or damaged licence replacement'),
    ('licensing.licence.change',       'licensing-service', 'طلب تعديل بيانات الرخصة',          'Request a change of licence details'),
    ('licensing.auto-renewal.manage',  'licensing-service', 'تفعيل التجديد التلقائي أو إيقافه', 'Switch automatic licence renewal on or off'),
    ('licensing.eye-test.record',      'licensing-service', 'تسجيل نتائج فحص النظر',            'Record eye test results');

update role set permissions = permissions || array['licensing.licence.renew', 'licensing.licence.replace',
        'licensing.licence.change', 'licensing.auto-renewal.manage'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'LICENSING_OFFICER';

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('OPTOMETRY_CENTRE', 'مركز فحص النظر', 'Optometry centre',
     'Partner role of an approved optometry centre: sends eye test results (DL-007).',
     true, array['licensing.eye-test.record'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

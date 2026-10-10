-- Licence status permissions of licensing-service (DL-058, DL-059; Increment 3 slice 3.5). Supervisors cancel
-- licences and record surrenders; holders and authorised partners enquire the status and verify no-objection
-- certificates. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('licensing.licence.cancel', 'licensing-service', 'إلغاء رخصة القيادة أو تسجيل التنازل عنها', 'Cancel a driving licence or record its surrender'),
    ('licensing.licence.status', 'licensing-service', 'الاستعلام عن حالة الرخصة',               'Enquire licence status and verify certificates'),
    ('licensing.noc.request',    'licensing-service', 'طلب شهادة عدم ممانعة',                  'Request a no-objection certificate');

update role set permissions = permissions || array['licensing.licence.cancel'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'LICENSING_SUPERVISOR';

update role set permissions = permissions || array['licensing.noc.request', 'licensing.licence.status'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'LICENSING_OFFICER';

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('LICENCE_STATUS_PARTNER', 'شريك الاستعلام عن الرخص', 'Licence status partner',
     'Authorised partner: enquires licence status and verifies no-objection certificates (DL-059).',
     true, array['licensing.licence.status'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

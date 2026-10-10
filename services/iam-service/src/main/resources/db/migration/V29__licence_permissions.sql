-- Licence permissions of licensing-service (DL-021…023, DL-036, DL-050; Increment 3 slice 3.3). Examination-service
-- hands passed files over with its own account; officers ask for licences; supervisors reverse transfers. Arabic texts
-- are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('licensing.transfer.receive', 'licensing-service', 'استلام ملفات الاختبار الناجحة',       'Receive passed examination files'),
    ('licensing.transfer.reverse', 'licensing-service', 'إلغاء تحويل ملف اختبار',              'Reverse an examination file transfer'),
    ('licensing.licence.issue',    'licensing-service', 'طلب إصدار رخصة قيادة',               'Request a driving licence issue'),
    ('licensing.licence.condition','licensing-service', 'تسجيل شروط الرخصة أو إزالتها',        'Record or remove licence conditions'),
    ('licensing.licence.read',     'licensing-service', 'عرض رخص القيادة وسجلها',              'Read driving licences and their history');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('LICENSING_OFFICER', 'موظف الترخيص', 'Licensing officer',
     'Asks for new licences from passed examination files and records licence conditions (DL-023, DL-050).',
     true, array['licensing.licence.issue', 'licensing.licence.condition', 'licensing.licence.read'], 1, now(),
     'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('LICENSING_SUPERVISOR', 'مشرف الترخيص', 'Licensing supervisor',
     'Reverses examination file transfers that no licence uses yet, with a reason (DL-036).',
     true, array['licensing.transfer.reverse', 'licensing.licence.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('EXAMINATION_TRANSFER_SENDER', 'مرسل ملفات الاختبار', 'Examination transfer sender',
     'Service role of examination-service: hands passed examination files to licensing (DL-036).',
     true, array['licensing.transfer.receive'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

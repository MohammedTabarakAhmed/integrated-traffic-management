-- Document and certificate permissions of document-service (CC-080..CC-085; ADR-051). Documents and certificates are
-- national; customers reach the documents they own without a permission (own scope). Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('document.create',              'document-service', 'إضافة المستندات وربطها',                 'Declare, upload and link documents'),
    ('document.read',                'document-service', 'عرض المستندات',                         'Read internal and public documents'),
    ('document.read.confidential',   'document-service', 'عرض المستندات السرية',                   'Read confidential and secret documents'),
    ('certificate.template.manage',  'document-service', 'نشر قوالب الشهادات والخطابات',            'Publish certificate and letter templates'),
    ('certificate.issue',            'document-service', 'إصدار الشهادات',                         'Issue certificates'),
    ('certificate.read',             'document-service', 'عرض الشهادات الصادرة',                    'Read issued certificates'),
    ('certificate.revoke',           'document-service', 'إلغاء الشهادات',                         'Revoke certificates');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('DOCUMENT_OFFICER', 'موظف المستندات', 'Document officer',
     'Receives customers'' documents and links them to records (CC-080, CC-085).',
     true, array['document.create', 'document.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('DOCUMENT_SENSITIVE_READER', 'مطلع على المستندات السرية', 'Sensitive document reader',
     'Reads confidential and secret documents such as medical reports (CC-080, SEC-014).',
     true, array['document.read', 'document.read.confidential'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('CERTIFICATE_ADMIN', 'مسؤول الشهادات', 'Certificate administrator',
     'Publishes certificate and letter templates and revokes certificates (CC-082, CC-083).',
     true, array['certificate.template.manage', 'certificate.read', 'certificate.revoke'], 1, now(),
     'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('CERTIFICATE_ISSUER', 'مُصدر الشهادات', 'Certificate issuer',
     'Staff and service accounts that issue certificates (CC-082).',
     true, array['certificate.issue', 'certificate.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

-- Audit trail permissions of audit-service (CC-120..CC-122; ADR-053). The Auditor actor of BRD section 3 has
-- read-only access to records and audit trails. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('audit.read',             'audit-service', 'البحث في سجل التدقيق',          'Search the audit trail'),
    ('audit.timeline.read',    'audit-service', 'عرض السجل الزمني لأي سجل',      'View the full history of any record'),
    ('audit.integrity.verify', 'audit-service', 'التحقق من سلامة سجل التدقيق',   'Verify the audit trail''s hash chain');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('AUDITOR', 'مدقق', 'Auditor',
     'Read-only access to records'' histories and the audit trail, and its integrity check (BRD section 3; CC-121).',
     true, array['audit.read', 'audit.timeline.read', 'audit.integrity.verify'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('RECORD_HISTORY_READER', 'مطلع على سجل التغييرات', 'Record history reader',
     'Views the full history of records on a timeline (CC-121).',
     true, array['audit.timeline.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

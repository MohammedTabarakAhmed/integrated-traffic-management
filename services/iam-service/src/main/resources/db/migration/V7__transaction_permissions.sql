-- Transaction permissions of transaction-service (CC-021..CC-023, CC-027; ADR-029) and the counter-staff role that
-- opens, submits and cancels transactions for customers. Each permission applies within the grant's region and module
-- scopes (CC-001). Customer self-service through the portal and app comes with customer identity (Q-049).
-- Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('transaction.create', 'transaction-service', 'فتح المعاملات وتقديمها',   'Open and submit transactions'),
    ('transaction.cancel', 'transaction-service', 'إلغاء المعاملات',           'Cancel transactions'),
    ('transaction.read',   'transaction-service', 'عرض المعاملات وسجل حالاتها', 'Read transactions and their status history');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('TRANSACTION_OFFICER', 'موظف المعاملات', 'Transaction officer',
     'Opens, submits and cancels transactions for customers at a service centre (CC-021, CC-022, CC-027).',
     true, array['transaction.create', 'transaction.cancel', 'transaction.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION');

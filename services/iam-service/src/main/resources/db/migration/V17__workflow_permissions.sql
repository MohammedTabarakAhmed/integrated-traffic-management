-- Workflow permissions of workflow-service (CC-060..CC-065; ADR-049) and roles. Work items are scoped by region and
-- service centre (CC-001, CC-061): a grant reaches the queues of its role within its regions and centres. Arabic texts
-- are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('workflow.definition.manage', 'workflow-service', 'نشر مسارات العمل',                    'Publish workflow versions'),
    ('workflow.definition.read',   'workflow-service', 'عرض مسارات العمل',                    'Read workflows'),
    ('workflow.item.create',       'workflow-service', 'بدء حالات العمل وإلغاؤها',              'Start and cancel work items (owning services)'),
    ('workflow.item.work',         'workflow-service', 'العمل على عناصر قوائم الانتظار',         'Work on items in the queues of one''s roles'),
    ('workflow.item.supervise',    'workflow-service', 'الإشراف على قوائم الانتظار والتصعيد',    'Assign work items and receive escalations'),
    ('workflow.item.respond',      'workflow-service', 'تقديم رد المتعامل نيابة عنه',           'Give a customer''s answer to a returned case');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('WORKFLOW_ADMIN', 'مسؤول مسارات العمل', 'Workflow administrator',
     'Publishes workflow versions: steps, queues, SLAs, escalation (CC-060).',
     true, array['workflow.definition.manage', 'workflow.definition.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('CASE_OFFICER', 'موظف معالجة الحالات', 'Case officer',
     'Works on cases in the queues of this role within the grant''s regions and centres (CC-061, CC-063).',
     true, array['workflow.item.work', 'workflow.definition.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('CASE_SUPERVISOR', 'مشرف الحالات', 'Case supervisor',
     'Assigns cases, also in bulk, and receives escalations of overdue cases (CC-062, CC-063).',
     true, array['workflow.item.supervise', 'workflow.item.work', 'workflow.definition.read'], 1, now(),
     'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('WORKFLOW_STARTER', 'بادئ مسارات العمل', 'Workflow starter',
     'Service accounts of modules that start and cancel cases (CC-060).',
     true, array['workflow.item.create'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('CUSTOMER_ASSISTANT', 'مساعد المتعاملين', 'Customer assistant',
     'Gives a customer''s answer to a returned case on their behalf (CC-065, staff-assisted).',
     true, array['workflow.item.respond'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

-- Business rule permissions of rules-service (CC-050..CC-053; ADR-048) and the maker, checker and evaluator roles
-- (CC-051, CC-002). Rules are national configuration with regional variants, so each permission applies in any grant.
-- RULE_EVALUATOR is for the service accounts of modules that ask for decisions. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('rules.version.submit',  'rules-service', 'تقديم إصدارات القواعد للاعتماد',   'Submit rule versions for approval'),
    ('rules.version.approve', 'rules-service', 'اعتماد إصدارات القواعد أو رفضها',  'Approve or reject rule versions'),
    ('rules.version.read',    'rules-service', 'عرض إصدارات القواعد',              'Read rule versions'),
    ('rules.version.test',    'rules-service', 'اختبار إصدارات القواعد',           'Test rule versions against sample cases'),
    ('rules.evaluate',        'rules-service', 'طلب قرار من القواعد',              'Evaluate rule sets (decisions are logged)'),
    ('rules.decision.read',   'rules-service', 'عرض سجل قرارات القواعد',           'Read the rule decision log');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('RULE_MAKER', 'معدّ القواعد', 'Rule maker',
     'Maintains business rules and tests them before submitting them for approval (CC-050, CC-052).',
     true, array['rules.version.submit', 'rules.version.read', 'rules.version.test'], 1, now(), 'SYSTEM_MIGRATION',
     now(), 'SYSTEM_MIGRATION'),
    ('RULE_CHECKER', 'معتمد القواعد', 'Rule checker',
     'Approves or rejects rule versions submitted by others and reviews logged decisions (CC-051, CC-053).',
     true, array['rules.version.approve', 'rules.version.read', 'rules.version.test', 'rules.decision.read'], 1,
     now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('RULE_EVALUATOR', 'مستخدم القواعد', 'Rule evaluator',
     'Service accounts of modules that ask for rule decisions (CC-050, CC-053).',
     true, array['rules.evaluate'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

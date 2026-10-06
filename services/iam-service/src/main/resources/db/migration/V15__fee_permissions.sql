-- Fee permissions of fees-service (CC-040..CC-047; ADR-046) and the maker and checker roles (CC-046, CC-002). Fees are
-- national configuration with regional variants, so each permission applies in any grant. Quotes need only a signed-in
-- user. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('fees.version.submit',  'fees-service', 'تقديم إصدارات الرسوم للاعتماد', 'Submit fee versions for approval'),
    ('fees.version.approve', 'fees-service', 'اعتماد إصدارات الرسوم أو رفضها', 'Approve or reject fee versions'),
    ('fees.version.read',    'fees-service', 'عرض إصدارات الرسوم',             'Read fee versions');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('FEE_MAKER', 'معدّ الرسوم', 'Fee maker',
     'Submits new fees and fee changes for approval (CC-040, CC-046).',
     true, array['fees.version.submit', 'fees.version.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('FEE_CHECKER', 'معتمد الرسوم', 'Fee checker',
     'Approves or rejects fee versions submitted by others (CC-046).',
     true, array['fees.version.approve', 'fees.version.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

-- Licence class permissions of licensing-service (DL-020, DL-062; Increment 3 slice 3.1). Classes are national
-- configuration with maker-checker. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('licensing.class.submit',  'licensing-service', 'اقتراح تغييرات فئات الرخص',  'Submit licence class versions'),
    ('licensing.class.approve', 'licensing-service', 'اعتماد تغييرات فئات الرخص',  'Approve or reject licence class versions'),
    ('licensing.class.read',    'licensing-service', 'عرض فئات الرخص وقواعدها',    'Read licence classes and their rules');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('LICENCE_CLASS_MAKER', 'مُعد فئات الرخص', 'Licence class maker',
     'Proposes licence classes and changes to their ages, tests, training hours, medical need and validity (DL-020, DL-062).',
     true, array['licensing.class.submit', 'licensing.class.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('LICENCE_CLASS_CHECKER', 'معتمد فئات الرخص', 'Licence class checker',
     'Approves or rejects proposed licence class versions; never their own (DL-020, CC-002).',
     true, array['licensing.class.approve', 'licensing.class.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION');

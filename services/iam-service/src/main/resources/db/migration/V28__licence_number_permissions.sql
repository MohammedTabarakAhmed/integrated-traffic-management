-- Licence number range permissions of licensing-service (DL-061; Increment 3 slice 3.2). Ranges are per region and
-- go through maker-checker. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('licensing.number-range.submit',  'licensing-service', 'اقتراح نطاقات أرقام الرخص',          'Submit licence number ranges'),
    ('licensing.number-range.approve', 'licensing-service', 'اعتماد نطاقات أرقام الرخص أو رفضها', 'Approve or reject licence number ranges'),
    ('licensing.number-range.read',    'licensing-service', 'عرض نطاقات أرقام الرخص وتقريرها',    'Read licence number ranges and their report');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('LICENCE_NUMBER_MAKER', 'مُعد نطاقات أرقام الرخص', 'Licence number range maker',
     'Proposes licence number ranges for a region (DL-061).',
     true, array['licensing.number-range.submit', 'licensing.number-range.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('LICENCE_NUMBER_CHECKER', 'معتمد نطاقات أرقام الرخص', 'Licence number range checker',
     'Approves or rejects proposed licence number ranges; never their own (DL-061, CC-002).',
     true, array['licensing.number-range.approve', 'licensing.number-range.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION');

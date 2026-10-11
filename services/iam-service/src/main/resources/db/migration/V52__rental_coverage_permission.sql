-- TR-032, VF-014, UAT-2: violation-service asks rental-service which contract covered a vehicle when a violation
-- has no identified driver, so the violation goes to the renter's traffic file. National read for the service account.

insert into permission (code, service, description_nl, description_en) values
    ('rental.contract.coverage', 'rental-service', 'الاستعلام عن عقد الإيجار الساري لمركبة في وقت محدد',
     'Find the rental contract covering a vehicle at a given time');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('RENTAL_COVERAGE_READER', 'مستعلم تغطية عقود الإيجار', 'Rental coverage reader',
     'Finds the renter responsible for a violation committed during a rental contract (TR-032, VF-014).', true,
     array['rental.contract.coverage'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

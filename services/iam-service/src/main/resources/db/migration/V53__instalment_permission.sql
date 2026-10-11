-- VF-071, VF-073: staff open the Authority's own instalment plans for a customer's open violations (violation V9,
-- ADR-062). Plans are read with violation.read.

insert into permission (code, service, description_nl, description_en) values
    ('violation.instalment.create', 'violation-service', 'فتح خطة تقسيط لمخالفات ملف مروري',
     'Open an instalment plan for the open violations of a traffic file');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('INSTALMENT_OFFICER', 'موظف خطط التقسيط', 'Instalment officer',
     'Opens instalment plans for customers'' open violations and reads them (VF-071, VF-073).', true,
     array['violation.instalment.create', 'violation.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

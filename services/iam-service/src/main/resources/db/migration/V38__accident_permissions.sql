-- Accident report creation and access (AC-001..AC-006).
insert into permission (code, service, description_nl, description_en) values
    ('accident.report', 'accident-service', 'Ongeval melden', 'Report an accident'),
    ('accident.read', 'accident-service', 'Ongevaldossiers bekijken', 'Read accident reports');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('ACCIDENT_OFFICER', 'Ongevalleninspecteur', 'Accident officer',
     'Records and reviews accident reports in an assigned region (AC-001..AC-006).', true,
     array['accident.report', 'accident.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

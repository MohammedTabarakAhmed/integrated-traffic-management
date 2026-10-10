-- Regional investigators decide fault after parties disagree (AC-003, AC-005, AC-009).
insert into permission (code, service, description_nl, description_en) values
    ('accident.investigate', 'accident-service', 'Ongevallen onderzoeken en schuld vaststellen',
     'Investigate accidents and decide fault');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('ACCIDENT_INVESTIGATOR', 'Ongevallenonderzoeker', 'Accident investigator',
     'Reviews disputed accident reports and records fault decisions (AC-003, AC-005).', true,
     array['accident.read', 'accident.investigate'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

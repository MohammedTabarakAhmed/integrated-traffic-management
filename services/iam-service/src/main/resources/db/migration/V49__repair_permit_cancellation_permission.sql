-- Regional accident investigators may cancel an issued repair permit with a recorded reason (AC-020).

insert into permission (code, service, description_nl, description_en) values
    ('accident.repair-permit.cancel', 'accident-service', 'Reparatievergunning intrekken',
     'Cancel an issued accident repair permit');

update role set permissions = permissions || array['accident.repair-permit.cancel'], version = version + 1,
    updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'ACCIDENT_INVESTIGATOR';

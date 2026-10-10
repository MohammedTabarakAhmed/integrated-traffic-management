-- Rental company and contract access (TR-020..TR-033).
insert into permission (code, service, description_nl, description_en) values
    ('rental.company.manage', 'rental-service', 'Verhuurders beheren', 'Register and manage rental companies'),
    ('rental.contract.record', 'rental-service', 'Huurcontract registreren', 'Record a rental contract'),
    ('rental.contract.read', 'rental-service', 'Huurcontract bekijken', 'Read a rental contract');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('RENTAL_PARTNER', 'Verhuurpartner', 'Rental partner',
     'An authorised rental company records contracts for its registered fleet (TR-020..TR-033).', true,
     array['rental.company.manage', 'rental.contract.record', 'rental.contract.read'], 1, now(), 'SYSTEM_MIGRATION',
     now(), 'SYSTEM_MIGRATION');

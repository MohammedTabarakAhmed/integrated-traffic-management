-- Garages and insurers verify permit authenticity; accident officers issue them (AC-020, AC-021).
insert into permission (code, service, description_nl, description_en) values
    ('accident.repair-permit.issue', 'accident-service', 'Reparatievergunning afgeven', 'Issue repair permits'),
    ('accident.repair-permit.verify', 'accident-service', 'Reparatievergunning controleren', 'Verify repair permits');

update role set permissions = permissions || array['accident.repair-permit.issue'], version = version + 1,
    updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'ACCIDENT_OFFICER';

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('REPAIR_PERMIT_VERIFIER', 'Reparatievergunning verifieren', 'Repair permit verifier',
     'Garages and insurers verify accident repair permits (AC-021).', true,
     array['accident.repair-permit.verify'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

-- Hold permissions of hold-eligibility-service (CC-030; ADR-033) and the staff role that places, reads and lifts holds.
-- Each permission applies within the grant's module scope (holds are national, Q-053). Lifting also needs a grant of
-- one of the roles the hold itself names as able to lift it (CC-030 "who may lift it").
-- Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('hold.place', 'hold-eligibility-service', 'وضع الحجوزات',                 'Place holds'),
    ('hold.lift',  'hold-eligibility-service', 'رفع الحجوزات',                 'Lift holds'),
    ('hold.read',  'hold-eligibility-service', 'عرض الحجوزات وسجل إصداراتها', 'Read holds and their version history');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('HOLD_OFFICER', 'موظف الحجوزات', 'Hold officer',
     'Places, reads and lifts holds on traffic files, licences, vehicles and plates in their modules (CC-030).',
     true, array['hold.place', 'hold.lift', 'hold.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

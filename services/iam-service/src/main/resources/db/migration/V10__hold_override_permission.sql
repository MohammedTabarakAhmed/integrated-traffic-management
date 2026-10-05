-- Single-transaction hold overrides of hold-eligibility-service (CC-034; ADR-035) and the supervisor role that may make
-- them. Owner decision 2026-10-05 (Q-055): the authorised supervisors are holders of hold.override in a grant reaching
-- the hold's module, so the role is granted per module like any other; the officer who placed a hold cannot override
-- it. The role also reads holds, to find the hold and see its overrides.
-- Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('hold.override', 'hold-eligibility-service', 'تجاوز حجز لمعاملة واحدة',
     'Override a hold for one transaction, with a reason and supporting document');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('HOLD_SUPERVISOR', 'مشرف الحجوزات', 'Hold supervisor',
     'Overrides a hold in their modules for one transaction, with a mandatory reason and supporting document '
     || '(CC-034); reads holds and their overrides.',
     true, array['hold.override', 'hold.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

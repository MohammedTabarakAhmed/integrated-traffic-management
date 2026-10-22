-- VF-046, VF-047: recording a driver's signed undertaking (pledge) and administrative preservation orders with their
-- references on violations (violation-service, ADR-068). Pledges are recorded by violation officers, preservation
-- orders by violation supervisors. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('violation.pledge.create', 'violation-service', 'تسجيل تعهد موقّع', 'Record a driver''s signed undertaking (pledge) on a violation'),
    ('violation.preservation.create', 'violation-service', 'تسجيل أوامر الحفظ الإداري', 'Record administrative preservation orders and their references on a violation');

update role set permissions = permissions || array['violation.pledge.create'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'VIOLATION_OFFICER';

update role set permissions = permissions || array['violation.preservation.create'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'VIOLATION_SUPERVISOR';

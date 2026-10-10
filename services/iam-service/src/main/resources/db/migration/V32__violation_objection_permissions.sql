-- Customer submissions and supervisor review of objections (VF-050..VF-052).

insert into permission (code, service, description_nl, description_en) values
    ('violation.objection.submit', 'violation-service', 'Bezwaar tegen boete indienen', 'Submit an objection to a violation'),
    ('violation.objection.review', 'violation-service', 'Bezwaar beoordelen', 'Review and decide violation objections');

update role set permissions = permissions || array['violation.objection.submit'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'VIOLATION_OFFICER';

update role set permissions = permissions || array['violation.objection.review'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'VIOLATION_SUPERVISOR';

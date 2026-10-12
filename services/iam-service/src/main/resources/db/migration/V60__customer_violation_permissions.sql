-- A customer objects online to their own violation (VF-050, XB-005) and asks for an own instalment plan for their own
-- open violations (VF-071); ADR-067 d. Held only through the implicit CUSTOMER role (V58). Arabic texts are DRAFT
-- (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('customer.violation.object',    'violation-service', 'الاعتراض على مخالفاتي',     'Object online to the signed-in customer''s own violations'),
    ('customer.instalment.request',  'violation-service', 'طلب تقسيط مخالفاتي',        'Ask online for an own instalment plan for the signed-in customer''s own violations');

update role set permissions = permissions || array['customer.violation.object', 'customer.instalment.request'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'CUSTOMER';

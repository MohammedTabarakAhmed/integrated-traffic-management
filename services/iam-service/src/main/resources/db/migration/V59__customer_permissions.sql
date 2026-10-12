-- Customer own-data permissions (ADR-067 d). Held only through the implicit CUSTOMER role (V58): no scopes, and each
-- /v1/me/... endpoint takes the customer from the token, never from a parameter. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('customer.trafficfile.read', 'traffic-file-service', 'عرض ملفي المروري',            'Read the signed-in customer''s own traffic file'),
    ('customer.violation.read',   'violation-service',    'عرض مخالفاتي ونقاطي السوداء', 'Read the signed-in customer''s own violations and black points'),
    ('customer.transaction.read', 'transaction-service',  'عرض معاملاتي',                'Read the signed-in customer''s own transactions'),
    ('customer.payment.make',     'payment-service',      'دفع مستحقاتي',                'Pay the signed-in customer''s own payables');

update role
   set permissions = array['customer.trafficfile.read', 'customer.violation.read', 'customer.transaction.read',
                           'customer.payment.make'],
       version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
 where code = 'CUSTOMER';

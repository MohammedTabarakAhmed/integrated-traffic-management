-- A customer reads their own in-app messages and keeps their own contact points and channel preferences online
-- (CC-073, CH-002; ADR-067 d). Changing them is a profile change and needs the SEC-002 step-up. Held only through the
-- implicit CUSTOMER role (V58). Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('customer.notification.read',        'notification-service', 'عرض رسائلي وتفضيلاتي',    'Read the signed-in customer''s own messages and channel preferences'),
    ('customer.notification.preferences', 'notification-service', 'تعديل وسائل تواصلي',      'Change the signed-in customer''s own contact points and channel preferences');

update role set permissions = permissions || array['customer.notification.read', 'customer.notification.preferences'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'CUSTOMER';

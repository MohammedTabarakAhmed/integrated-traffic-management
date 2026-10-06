-- Notification permissions of notification-service (CC-070..CC-074; ADR-050). Notifications are national; customers
-- reach their own inbox and preferences without a permission (own scope). Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('notification.template.manage',     'notification-service', 'نشر قوالب الإشعارات',             'Publish notification templates'),
    ('notification.template.read',       'notification-service', 'عرض قوالب الإشعارات',             'Read notification templates'),
    ('notification.send',                'notification-service', 'إرسال الإشعارات',                 'Send notifications (services)'),
    ('notification.read',                'notification-service', 'عرض إشعارات المتعاملين وحالتها',    'Read customers'' notifications and delivery status'),
    ('notification.recipient.manage',    'notification-service', 'إدارة بيانات تواصل المتعاملين',      'Manage customers'' contact points and preferences'),
    ('notification.receipt',             'notification-service', 'إرسال إيصالات التسليم',            'Report delivery receipts (messaging gateways)'),
    ('notification.subscription.manage', 'notification-service', 'إدارة اشتراكات الأحداث',           'Manage event subscriptions'),
    ('notification.expiry.register',     'notification-service', 'تسجيل تواريخ الانتهاء للتذكير',      'Register expiries for reminders (services)');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('NOTIFICATION_ADMIN', 'مسؤول الإشعارات', 'Notification administrator',
     'Publishes bilingual templates and decides which events notify whom (CC-070, CC-071).',
     true, array['notification.template.manage', 'notification.template.read', 'notification.subscription.manage'],
     1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('NOTIFICATION_SENDER', 'مرسل الإشعارات', 'Notification sender',
     'Service accounts of modules that send notifications and register expiries (CC-070, CC-072).',
     true, array['notification.send', 'notification.expiry.register', 'notification.template.read'], 1, now(),
     'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('NOTIFICATION_SUPPORT', 'دعم الإشعارات', 'Notification support',
     'Customer support: reads delivery status and keeps contact points and preferences for customers (CC-073, CC-074).',
     true, array['notification.read', 'notification.recipient.manage'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('MESSAGING_GATEWAY', 'بوابة الرسائل', 'Messaging gateway',
     'Account of the SMS, email and push providers'' adapter for delivery receipts (CC-074).',
     true, array['notification.receipt'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

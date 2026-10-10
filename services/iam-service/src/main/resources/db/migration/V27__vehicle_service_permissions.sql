-- Increment 2 permissions of vehicle-service (VL-002…VL-039; ADR-059), scoped by the vehicle's or series' region.
-- Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('vehicle.service',            'vehicle-service', 'إجراء معاملات المركبات واللوحات',      'Start vehicle and plate transactions (transfer, change, card, de-registration, certificates, permits, plates)'),
    ('vehicle.approve',            'vehicle-service', 'اعتماد إقرارات المركبات وفحوص الأمن',   'Approve public vehicle declarations and security inspections'),
    ('vehicle.ban.manage',         'vehicle-service', 'إدارة قائمة المركبات الممنوعة',         'Ban vehicles from use and lift bans'),
    ('vehicle.mortgage.manage',    'vehicle-service', 'تسجيل الرهون وفكها',                    'Register, release and transfer vehicle mortgages (banks)'),
    ('vehicle.customs.feed',       'vehicle-service', 'إرسال شهادات الجمارك والوكلاء',         'Send customs clearance and dealer certificates'),
    ('vehicle.card.manage',        'vehicle-service', 'طباعة بطاقات التسجيل وتسليمها',         'Print and deliver registration cards'),
    ('vehicle.insurance.recovery', 'vehicle-service', 'إصدار شهادات الاسترداد للتأمين',        'Request insurance recovery certificates (insurers)'),
    ('vehicle.plate.auction',      'vehicle-service', 'إدارة مزادات الأرقام المميزة',           'Run premium number auctions and report partner bids'),
    ('vehicle.plate.factory',      'vehicle-service', 'تحديث أوامر تصنيع اللوحات',             'Report plate production and delivery (factories)');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('VEHICLE_SERVICE_OFFICER', 'موظف خدمات المركبات', 'Vehicle services officer',
     'Starts transfers, changes, card replacements, de-registrations, certificates, permits and plate transactions (VL-012…VL-039).',
     true, array['vehicle.service', 'vehicle.read', 'vehicle.register'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('VEHICLE_SUPERVISOR', 'مشرف المركبات', 'Vehicle supervisor',
     'Approves declarations and security inspections and lifts bans; never their own (VL-006, VL-018, VL-019).',
     true, array['vehicle.approve', 'vehicle.ban.manage', 'vehicle.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('BANK_INTEGRATION', 'تكامل البنوك', 'Bank integration',
     'Bank accounts that register, release and transfer mortgages (VL-021).',
     true, array['vehicle.mortgage.manage'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('CUSTOMS_INTEGRATION', 'تكامل الجمارك والوكلاء', 'Customs and dealer integration',
     'Customs and dealer feeds that send clearance and dealer certificates (VL-002).',
     true, array['vehicle.customs.feed'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('CARD_PRINT_CENTRE', 'مركز طباعة البطاقات', 'Card print centre',
     'Print centres and couriers that move registration cards on (VL-022).',
     true, array['vehicle.card.manage'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('INSURER_INTEGRATION', 'تكامل شركات التأمين', 'Insurer integration',
     'Insurers that request recovery certificates (VL-017).',
     true, array['vehicle.insurance.recovery'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('PLATE_AUCTION_ADMIN', 'مسؤول مزادات اللوحات', 'Plate auction administrator',
     'Runs premium number auctions and reports partner bids (VL-034).',
     true, array['vehicle.plate.auction', 'vehicle.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('PLATE_FACTORY', 'مصنع اللوحات', 'Plate factory',
     'Factories that report production, delivery and serial numbers (VL-036).',
     true, array['vehicle.plate.factory'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

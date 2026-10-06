-- Vehicle permissions of vehicle-service (VL-*; ADR-055), scoped by the vehicle's region. Arabic texts are DRAFT
-- (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('vehicle.create',            'vehicle-service', 'إضافة المركبات إلى السجل',          'Add vehicles to the register'),
    ('vehicle.read',              'vehicle-service', 'عرض ملفات المركبات',               'Read vehicle files and history'),
    ('vehicle.register',          'vehicle-service', 'تسجيل المركبات وتجديد تسجيلها',      'Start first registration and renewal'),
    ('vehicle.inspection.record', 'vehicle-service', 'تسجيل نتائج الفحص الفني',           'Record technical inspection results'),
    ('vehicle.plate.manage',      'vehicle-service', 'إدارة سلاسل أرقام اللوحات',          'Manage plate number series');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('VEHICLE_OFFICER', 'موظف تسجيل المركبات', 'Vehicle registration officer',
     'Adds vehicles, starts first registrations and renewals for customers (VL-001, VL-010, VL-011).',
     true, array['vehicle.create', 'vehicle.read', 'vehicle.register'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('PLATE_ADMIN', 'مسؤول اللوحات', 'Plate administrator',
     'Manages plate number series per region, category, colour and design (VL-030).',
     true, array['vehicle.plate.manage', 'vehicle.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('INSPECTION_CENTRE', 'مركز الفحص الفني', 'Inspection centre',
     'Accounts of inspection centres that send results (VL-025, VL-040).',
     true, array['vehicle.inspection.record'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

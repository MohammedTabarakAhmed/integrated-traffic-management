-- Violation permissions of violation-service (VF-*; ADR-056), scoped by the violation's region. Arabic texts are DRAFT
-- (Q-006). SUSPENSION_HOLD_PLACER only places holds: violation-service's own account uses it for licence suspensions
-- (VF-032) without the right to lift or read other holds.

insert into permission (code, service, description_nl, description_en) values
    ('violation.catalogue.manage', 'violation-service', 'إدارة دليل المخالفات',           'Publish violation catalogue versions'),
    ('violation.record',           'violation-service', 'تسجيل المخالفات',               'Record violations'),
    ('violation.read',             'violation-service', 'عرض المخالفات والنقاط السوداء',   'Read violations and black point accounts'),
    ('violation.cancel',           'violation-service', 'إلغاء المخالفات',               'Cancel open violations with a reason');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('VIOLATION_OFFICER', 'ضابط المخالفات', 'Violation officer',
     'Records violations in their regions and reads violations and black points (VF-010, VF-014, VF-016).',
     true, array['violation.record', 'violation.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('VIOLATION_SUPERVISOR', 'مشرف المخالفات', 'Violation supervisor',
     'Cancels open violations with a reason code from the configured list (VF-021).',
     true, array['violation.cancel', 'violation.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('VIOLATION_CATALOGUE_ADMIN', 'مسؤول دليل المخالفات', 'Violation catalogue administrator',
     'Publishes violation catalogue versions with effective dates and regional variants (VF-001..VF-004).',
     true, array['violation.catalogue.manage', 'violation.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('SUSPENSION_HOLD_PLACER', 'واضع حجوزات الإيقاف', 'Suspension hold placer',
     'Service accounts that place licence suspension holds (VF-032).',
     true, array['hold.place'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

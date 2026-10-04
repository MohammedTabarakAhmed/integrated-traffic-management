-- BRD starting list for hold types (CC-031). Seeded by migration as the initial load; every later change goes through
-- maker-checker (CC-114). National-language names are DRAFT Arabic translations pending Authority wording (Q-006).
insert into reference_list (code, name_nl, name_en, parent_list_code, created_at, created_by)
values ('HOLD_TYPE', 'أنواع الحجوزات والقيود', 'Hold types', null, now(), 'SYSTEM_MIGRATION');

insert into reference_item (id, list_code, code, region_code, name_nl, name_en, active, effective_from, effective_to,
                            parent_code, external_codes, sort_order, version, created_at, created_by, updated_at,
                            updated_by)
select gen_random_uuid(), 'HOLD_TYPE', v.code, null, v.name_nl, v.name_en, true, date '2026-01-01', null, null,
       '{}'::jsonb, v.sort_order, 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'
from (values
    ('UNPAID_FINES',                'مخالفات غير مدفوعة',              'Unpaid fines',                                   10),
    ('COURT_ORDER',                 'أمر محكمة',                       'Court order',                                    20),
    ('POLICE_CIRCULAR',             'تعميم شرطي (مركبة أو شخص مطلوب)',  'Police circular (wanted vehicle or person)',     30),
    ('DRIVING_BAN',                 'حظر القيادة',                     'Driving ban',                                    40),
    ('IMPOUND',                     'حجز المركبة',                     'Impound',                                        50),
    ('MORTGAGE',                    'رهن',                             'Mortgage',                                       60),
    ('EXPIRED_INSURANCE',           'تأمين منتهي',                     'Expired insurance',                              70),
    ('FAILED_INSPECTION',           'رسوب في الفحص الفني',             'Failed inspection',                              80),
    ('ADMINISTRATIVE_BAN',          'حظر إداري',                       'Administrative ban',                             90),
    ('DECEASED',                    'متوفى',                           'Deceased',                                      100),
    ('EXTERNAL_TRAVEL_RESTRICTION', 'قيود سفر من جهات خارجية',          'Travel-related restriction from external agency', 110)
) as v(code, name_nl, name_en, sort_order);

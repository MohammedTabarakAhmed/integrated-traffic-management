-- VF-023 (ADR-058): violation-service's own account places UNPAID_FINES holds on violations left unpaid and lifts them
-- once the violation is paid or cancelled. It never reads other holds. Arabic texts are DRAFT (Q-006).

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('UNPAID_FINE_HOLD_MANAGER', 'مدير حجوزات المخالفات غير المدفوعة', 'Unpaid fine hold manager',
     'Service accounts that place and lift holds for unpaid violations (VF-023).',
     true, array['hold.place', 'hold.lift'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

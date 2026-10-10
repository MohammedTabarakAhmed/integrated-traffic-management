-- The device-service service account records captures accepted by its quality workflow (VF-010, VF-011).

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('DEVICE_VIOLATION_RECORDER', 'Apparaatregistratie', 'Device violation recorder',
     'Records approved device captures as violations through the service API.', true,
     array['violation.record'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

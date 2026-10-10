-- Device registration, calibrated capture intake and manual quality review (VF-010..VF-013, VF-080..VF-082).

insert into permission (code, service, description_nl, description_en) values
    ('device.manage', 'device-service', 'Handhavingsapparaten beheren', 'Register and calibrate enforcement devices'),
    ('device.read', 'device-service', 'Handhavingsapparaten bekijken', 'Read enforcement device records'),
    ('device.capture.ingest', 'device-service', 'Apparaatmeldingen ontvangen', 'Submit enforcement device captures'),
    ('device.capture.review', 'device-service', 'Apparaatmeldingen beoordelen', 'Review quality exceptions in device captures');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('DEVICE_ADMIN', 'Apparaatbeheerder', 'Device administrator',
     'Registers enforcement devices and records calibration history (VF-080, VF-081).', true,
     array['device.manage', 'device.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('DEVICE_GATEWAY', 'Apparaatgateway', 'Device gateway',
     'Submits signed device capture batches for quality screening (VF-010, VF-012).', true,
     array['device.capture.ingest'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('DEVICE_REVIEWER', 'Beoordelaar apparaatmeldingen', 'Device capture reviewer',
     'Reviews exception captures before violation recording (VF-011).', true,
     array['device.capture.review', 'device.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');


-- DL-062, Q-076 a: licensing-service reads the holder's customer category (file type) and residency end from the
-- traffic file instead of taking them from the officer; document-service prints the holder's name on the certificates
-- it renders for other modules (CC-082). Service role with national read and nothing else. Arabic texts are DRAFT
-- (Q-006).

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('TRAFFIC_FILE_READER_SERVICE', 'قارئ الملفات المرورية (خدمة)', 'Traffic file reader (service)',
     'Service role of licensing-service and document-service: reads the holder''s customer category, residency end '
     || 'and name (DL-062, CC-082).', true,
     array['trafficfile.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

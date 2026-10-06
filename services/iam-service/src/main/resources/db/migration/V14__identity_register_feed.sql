-- Identity register change notifications (DL-010; ADR-045): the role of the register adapter's service account,
-- which may only post notifications. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('trafficfile.register.notify', 'traffic-file-service', 'إرسال إشعارات تغيير سجل الهوية',
     'Send identity register change notifications');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('IDENTITY_REGISTER_FEED', 'تغذية سجل الهوية', 'Identity register feed',
     'Service account of the identity register adapter: posts change notifications (DL-010, ICD-01).',
     true, array['trafficfile.register.notify'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

-- Ban list permissions of traffic-file-service (DL-013; ADR-043) and the role that keeps the list. The list is
-- national, so each permission applies in any grant. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('trafficfile.ban.place', 'traffic-file-service', 'إدراج الأشخاص في قائمة الحظر', 'Put people on the ban list'),
    ('trafficfile.ban.lift',  'traffic-file-service', 'رفع الحظر عن الأشخاص',        'Lift bans'),
    ('trafficfile.ban.read',  'traffic-file-service', 'عرض قائمة الحظر',             'Read the ban list');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('BAN_LIST_OFFICER', 'موظف قائمة الحظر', 'Ban list officer',
     'Places, reads and lifts entries of the ban list of people barred from services (DL-013).',
     true, array['trafficfile.ban.place', 'trafficfile.ban.lift', 'trafficfile.ban.read'], 1, now(),
     'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

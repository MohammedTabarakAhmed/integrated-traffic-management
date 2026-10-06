-- Appointment permissions of appointment-service (CC-090..CC-093; ADR-052). Calendars, bookings for customers and
-- attendance are scoped by the centre's region; customers book their own appointments without a permission (own
-- scope). Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('appointment.calendar.manage', 'appointment-service', 'إدارة تقاويم المواعيد والموارد',  'Manage centre calendars, resources and booking settings'),
    ('appointment.book',            'appointment-service', 'حجز المواعيد للمتعاملين',         'Book, change and cancel appointments for customers'),
    ('appointment.attendance',      'appointment-service', 'تسجيل حضور المواعيد',            'Record attendance and no-shows'),
    ('appointment.read',            'appointment-service', 'عرض مواعيد المتعاملين والمراكز',   'Read customers'' and centres'' appointments');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('APPOINTMENT_ADMIN', 'مسؤول المواعيد', 'Appointment administrator',
     'Keeps centre working hours, holidays, blackouts, resources and booking settings (CC-090, CC-092).',
     true, array['appointment.calendar.manage', 'appointment.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('APPOINTMENT_AGENT', 'موظف المواعيد', 'Appointment agent',
     'Books and records attendance for customers at the counter or call centre (CC-091, CC-093).',
     true, array['appointment.book', 'appointment.attendance', 'appointment.read'], 1, now(), 'SYSTEM_MIGRATION',
     now(), 'SYSTEM_MIGRATION');

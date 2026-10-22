-- CH-031: centre queue management (appointment-service, ADR-070). Counter work belongs to the appointment agent
-- and administrator (V20); the dedicated permission keeps a viewer a viewer. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('appointment.queue.manage', 'appointment-service', 'إدارة طوابير مراكز الخدمة',
     'Issue, call, finish and close walk-up queue tickets at a centre');

update role set permissions = permissions || array['appointment.queue.manage'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code in ('APPOINTMENT_AGENT', 'APPOINTMENT_ADMIN');

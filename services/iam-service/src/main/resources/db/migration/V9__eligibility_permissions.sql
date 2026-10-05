-- Eligibility checks of hold-eligibility-service (CC-032; ADR-034) and the role that may run them. A check answers only
-- whether holds block a service for a subject, with a customer-safe reason and next step; it shows no hold details,
-- so it is not limited to the hold's module. Granted to transaction-service's own service account, which checks
-- eligibility at submission.
-- Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('eligibility.check', 'hold-eligibility-service', 'التحقق من أهلية تقديم الخدمة',
     'Check whether holds block a service for a subject');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('ELIGIBILITY_CHECKER', 'مدقق الأهلية', 'Eligibility checker',
     'Checks before submission whether holds block a service for a subject (CC-032). For the service accounts of '
     || 'services that take submissions.',
     true, array['eligibility.check'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

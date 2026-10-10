-- Paper violation book issue, capture entry and reconciliation (VF-013).
insert into permission (code,service,description_nl,description_en) values
    ('violation.book.issue','violation-service','Overtredingsboek uitgeven','Issue or report a lost violation paper book'),
    ('violation.book.record','violation-service','Papieren overtreding registreren','Link an entered paper report to a violation'),
    ('violation.book.reconcile','violation-service','Overtredingsboek afstemmen','Reconcile a violation paper book');
update role set permissions=permissions||array['violation.book.issue','violation.book.record'],version=version+1,updated_at=now(),updated_by='SYSTEM_MIGRATION'
where code='VIOLATION_OFFICER';
update role set permissions=permissions||array['violation.book.reconcile'],version=version+1,updated_at=now(),updated_by='SYSTEM_MIGRATION'
where code='VIOLATION_SUPERVISOR';

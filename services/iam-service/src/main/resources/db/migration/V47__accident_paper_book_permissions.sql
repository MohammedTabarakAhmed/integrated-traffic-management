-- Paper accident book issue, field entry and reconciliation (AC-022).
insert into permission (code,service,description_nl,description_en) values
    ('accident.paper-book.issue','accident-service','Ongevallenboek uitgeven','Issue or report a lost accident paper book'),
    ('accident.paper-book.record','accident-service','Papieren ongeval registreren','Enter a paper accident report'),
    ('accident.paper-book.reconcile','accident-service','Ongevallenboek afstemmen','Reconcile an accident paper book');
update role set permissions=permissions||array['accident.paper-book.issue','accident.paper-book.record'],version=version+1,updated_at=now(),updated_by='SYSTEM_MIGRATION'
where code='ACCIDENT_OFFICER';
update role set permissions=permissions||array['accident.paper-book.reconcile'],version=version+1,updated_at=now(),updated_by='SYSTEM_MIGRATION'
where code='ACCIDENT_SUPERVISOR';

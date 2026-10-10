-- Accident objections, hit-and-run investigation and partner referral/exchange (AC-007..AC-012).
insert into permission (code,service,description_nl,description_en) values
    ('accident.objection.submit','accident-service','Ongevallenbesluit betwisten','File an accident fault objection'),
    ('accident.objection.review','accident-service','Ongevallenbezwaar beoordelen','Review an accident objection'),
    ('accident.prosecution.refer','accident-service','Ongeval naar justitie verwijzen','Refer a serious accident to prosecution'),
    ('accident.external.send','accident-service','Ongevalspartner informeren','Queue ambulance and insurer accident exchanges');
update role set permissions=permissions||array['accident.external.send'],version=version+1,updated_at=now(),updated_by='SYSTEM_MIGRATION'
where code='ACCIDENT_OFFICER';
insert into role (code,name_nl,name_en,description,active,permissions,version,created_at,created_by,updated_at,updated_by) values
    ('ACCIDENT_PARTY','Ongevalbetrokkene','Accident party','Files an objection to a fault decision (AC-009).',true,array['accident.objection.submit'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION'),
    ('ACCIDENT_SUPERVISOR','Ongevalssupervisor','Accident senior investigator','Reviews objections and refers serious accidents to prosecution (AC-009, AC-011).',true,array['accident.objection.review','accident.prosecution.refer'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION');

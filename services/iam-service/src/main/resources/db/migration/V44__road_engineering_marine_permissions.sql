-- Geography, road-use permits and marine craft (RE-001..RE-033).
insert into permission (code,service,description_nl,description_en) values
    ('geo.node.manage','geo-service','Geografie beheren','Manage geographic nodes'),
    ('geo.node.read','geo-service','Geografie bekijken','Read geographic nodes'),
    ('road-permit.apply','road-permit-service','Wegvergunning aanvragen','Apply for road permits'),
    ('road-permit.approve','road-permit-service','Wegvergunning goedkeuren','Approve affected road permits'),
    ('road-permit.manage','road-permit-service','Wegvergunning beheren','Manage road permit lifecycle'),
    ('marine.craft.manage','marine-service','Vaartuig beheren','Manage marine craft'),
    ('marine.inspection.record','marine-service','Vaartuiginspectie vastleggen','Record marine inspections'),
    ('marine.operator-licence.issue','marine-service','Vaartuigbestuurdersbewijs afgeven','Issue marine operator licences');
insert into role (code,name_nl,name_en,description,active,permissions,version,created_at,created_by,updated_at,updated_by) values
    ('GEO_ADMIN','Geografiebeheerder','Geography administrator','Maintains the national bilingual geography hierarchy (RE-001..RE-005).',true,array['geo.node.manage','geo.node.read'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION'),
    ('ROAD_PERMIT_OFFICER','Wegvergunningmedewerker','Road permit officer','Submits road-use permit applications (RE-010..RE-023).',true,array['road-permit.apply'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION'),
    ('ROAD_AUTHORITY_APPROVER','Wegbeheerder goedkeurder','Road authority approver','Reviews road-use permits in their authority scope (RE-022).',true,array['road-permit.approve'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION'),
    ('ROAD_PERMIT_ADMIN','Wegvergunningbeheerder','Road permit administrator','Issues and manages approved road-use permits (RE-020..RE-023).',true,array['road-permit.manage'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION'),
    ('MARINE_REGISTRY_OFFICER','Vaartuigregistratie','Marine registry officer','Registers craft and issues licences (RE-030..RE-033).',true,array['marine.craft.manage','marine.operator-licence.issue'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION'),
    ('MARINE_INSPECTOR','Vaartuiginspecteur','Marine inspector','Records craft inspections (RE-031).',true,array['marine.inspection.record'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION');

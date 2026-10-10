-- Cross-border exchange operations and partner mapping (XB-001..XB-008).
insert into permission (code,service,description_nl,description_en) values
    ('crossborder.partner.manage','crossborder-service','Partnerstaat beheren','Manage partner-state configuration'),
    ('crossborder.mapping.manage','crossborder-service','Partnercodes beheren','Manage partner code mappings'),
    ('crossborder.exchange','crossborder-service','Grensoverschrijdende gegevensuitwisseling','Exchange cross-border records'),
    ('crossborder.objection.submit','crossborder-service','Buitenlandse boete betwisten','Submit an objection to an external violation'),
    ('crossborder.settlement.read','crossborder-service','Partnerafrekening bekijken','Read partner settlement totals'),
    ('crossborder.message.read','crossborder-service','Uitwisselingsbericht bekijken','Read exchange message status');
insert into role (code,name_nl,name_en,description,active,permissions,version,created_at,created_by,updated_at,updated_by) values
    ('CROSSBORDER_ADMIN','Grensbeheerder','Cross-border administrator','Configures partner states and code mappings (XB-007..XB-008).',true,array['crossborder.partner.manage','crossborder.mapping.manage','crossborder.message.read'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION'),
    ('CROSSBORDER_EXCHANGER','Grensuitwisseling','Cross-border exchange officer','Sends and receives partner exchange records (XB-001..XB-003, XB-006, XB-008).',true,array['crossborder.exchange','crossborder.message.read'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION'),
    ('CROSSBORDER_OBJECTION_OFFICER','Grensbezwaar','Cross-border objection officer','Submits customer objections to partner states (XB-005).',true,array['crossborder.objection.submit'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION'),
    ('CROSSBORDER_SETTLEMENT_AUDITOR','Grensafrekening','Cross-border settlement auditor','Reads partner-state settlement totals (XB-004).',true,array['crossborder.settlement.read'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION');

-- Training permit applications, tests and lifecycle (TR-001..TR-008).
insert into permission (code,service,description_nl,description_en) values
    ('permit.type.manage','permit-service','Type vergunning beheren','Configure permit types'),
    ('permit.application.submit','permit-service','Vergunning aanvragen','Submit permit applications'),
    ('permit.test.book','permit-service','Vergunningstest plannen','Book permit tests'),
    ('permit.test.record','permit-service','Vergunningstest vastleggen','Record permit test results'),
    ('permit.lifecycle.manage','permit-service','Vergunningstatus beheren','Manage permit lifecycle');
insert into role (code,name_nl,name_en,description,active,permissions,version,created_at,created_by,updated_at,updated_by) values
    ('TRAINING_PERMIT_OFFICER','Vergunningmedewerker','Training permit officer','Submits training permits and books examinations (TR-001..TR-003).',true,array['permit.application.submit','permit.test.book'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION'),
    ('TRAINING_PERMIT_ADMIN','Vergunningbeheerder','Training permit administrator','Configures and manages permit examinations and lifecycle (TR-001..TR-008).',true,array['permit.type.manage','permit.test.record','permit.lifecycle.manage'],1,now(),'SYSTEM_MIGRATION',now(),'SYSTEM_MIGRATION');

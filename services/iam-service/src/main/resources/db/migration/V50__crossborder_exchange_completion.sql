-- Increment 9 completion (XB-004..XB-008, DL-064): maker-checker approval of partner settings and code mappings,
-- accident/permit/licence data exchange, settlement statements; violation-service reads vehicles by plate (XB-002).

insert into permission (code, service, description_nl, description_en) values
    ('crossborder.proposal.approve', 'crossborder-service', 'اعتماد إعدادات الدول الشريكة ومطابقة الرموز',
     'Approve or reject proposed partner settings and code mappings'),
    ('crossborder.data.exchange', 'crossborder-service', 'تبادل بيانات الحوادث والتصاريح والرخص',
     'Send accident, permit and licence data to partner states'),
    ('crossborder.settlement.issue', 'crossborder-service', 'إصدار كشوف تسوية الدول الشريكة',
     'Issue settlement statements for a partner state');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('CROSSBORDER_APPROVER', 'معتمد إعدادات التبادل عبر الحدود', 'Cross-border settings approver',
     'Approves partner settings and code mappings proposed by another administrator (XB-007, XB-008).', true,
     array['crossborder.proposal.approve', 'crossborder.message.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('CROSSBORDER_DATA_OFFICER', 'موظف بيانات التبادل عبر الحدود', 'Cross-border data officer',
     'Sends accident, permit and licence data where the agreement allows (XB-006, DL-064).', true,
     array['crossborder.data.exchange', 'crossborder.message.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('CROSSBORDER_SETTLEMENT_OFFICER', 'موظف التسويات عبر الحدود', 'Cross-border settlement officer',
     'Issues periodic settlement statements with partner states (XB-004).', true,
     array['crossborder.settlement.issue', 'crossborder.settlement.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION'),
    ('VEHICLE_READER', 'مستعلم المركبات', 'Vehicle reader',
     'Reads vehicle files, for example to find the owner of a partner-state violation by plate (XB-002).', true,
     array['vehicle.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

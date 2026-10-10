-- Refund, settlement and reconciliation permissions (FN-006, FN-012, FN-040, FN-041).

insert into permission (code, service, description_nl, description_en) values
    ('payment.refund.request', 'payment-service', 'Terugbetaling aanvragen', 'Request a refund'),
    ('payment.refund.approve', 'payment-service', 'Terugbetaling beoordelen', 'Approve or reject a refund'),
    ('payment.settlement.import', 'payment-service', 'Partnerafrekening importeren', 'Import partner settlement data'),
    ('payment.finance.read', 'payment-service', 'Financiële uitzonderingen bekijken', 'Read finance reconciliation exceptions'),
    ('payment.finance.resolve', 'payment-service', 'Financiële uitzondering oplossen', 'Resolve a reconciliation exception');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('FINANCE_REFUND_MAKER', 'Terugbetalingsmedewerker', 'Refund maker',
     'Requests refunds against confirmed payments (FN-012).', true, array['payment.refund.request'],
     1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('FINANCE_REFUND_CHECKER', 'Terugbetalingscontroleur', 'Refund checker',
     'Approves refunds within configured role approval limits (FN-012, CC-003).', true,
     array['payment.refund.approve'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('FINANCE_RECONCILER', 'Financieel afstemmingsmedewerker', 'Finance reconciler',
     'Imports partner settlement data and resolves unmatched items (FN-006, FN-040, FN-041).', true,
     array['payment.settlement.import', 'payment.finance.read', 'payment.finance.resolve'],
     1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

-- Cashiers open and close their own tills (FN-020).

insert into permission (code, service, description_nl, description_en) values
    ('payment.till.manage', 'payment-service', 'Kas openen en sluiten', 'Open and close a cashier till');

update role set permissions = permissions || array['payment.till.manage'], version = version + 1,
    updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'CASHIER';


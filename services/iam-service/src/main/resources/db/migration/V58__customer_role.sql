-- Customer sign-in (ADR-067 b). Every signed-in customer gets this role implicitly, without scopes; iam-service
-- refuses to assign it and only its customer.* permissions count. Its permissions arrive with the customer
-- endpoints (ADR-067 d). Arabic text is DRAFT (Q-006).

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('CUSTOMER', 'عميل', 'Customer',
     'Implicit role of a customer signed in through the national digital ID; never assigned (ADR-067).', true,
     array[]::text[], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

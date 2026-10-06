-- Payment permissions of payment-service (FN-001..FN-005, FN-010, FN-011, FN-014, FN-022; ADR-054). Collection and
-- receipt cancellation are scoped by region; customers pay their own baskets without a permission (own scope).
-- Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('payment.payable.register', 'payment-service', 'تسجيل المبالغ المستحقة للدفع',         'Register and withdraw payable items (owning services)'),
    ('payment.basket.create',    'payment-service', 'الدفع نيابة عن المتعاملين إلكترونياً',   'Start online payments of baskets made by others'),
    ('payment.collect',          'payment-service', 'تحصيل المدفوعات في المراكز',           'Collect counter payments (cash, card terminal, cheque)'),
    ('payment.gateway.callback', 'payment-service', 'إرسال تأكيدات بوابة الدفع',             'Send payment gateway confirmations (gateway adapter)'),
    ('payment.receipt.read',     'payment-service', 'الاستعلام عن الإيصالات والمدفوعات',      'Enquire receipts and payments'),
    ('payment.receipt.cancel',   'payment-service', 'إلغاء الإيصالات في اليوم نفسه',          'Cancel receipts on their business day (supervisors)'),
    ('payment.exception.read',   'payment-service', 'عرض استثناءات المدفوعات',               'Read payment exceptions (finance queue)');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('PAYABLE_REGISTRAR', 'مسجل المستحقات', 'Payable registrar',
     'Service accounts of modules that register what customers owe (FN-002).',
     true, array['payment.payable.register'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('CASHIER', 'أمين الصندوق', 'Cashier',
     'Collects counter payments and looks up receipts (FN-001, FN-014).',
     true, array['payment.collect', 'payment.receipt.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('CASHIER_SUPERVISOR', 'مشرف الصناديق', 'Cashier supervisor',
     'Cancels receipts on their business day with a reason and follows payment exceptions (FN-011, FN-005).',
     true, array['payment.receipt.cancel', 'payment.receipt.read', 'payment.exception.read'], 1, now(),
     'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('PAYMENT_GATEWAY_ADAPTER', 'محوّل بوابة الدفع', 'Payment gateway adapter',
     'Account of the gateway adapter that relays signed confirmations (FN-004, ICD-03).',
     true, array['payment.gateway.callback'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('FINANCE_OFFICER', 'موظف المالية', 'Finance officer',
     'Enquires receipts and works the payment exception queue (FN-005, FN-014).',
     true, array['payment.receipt.read', 'payment.exception.read'], 1, now(), 'SYSTEM_MIGRATION', now(),
     'SYSTEM_MIGRATION');

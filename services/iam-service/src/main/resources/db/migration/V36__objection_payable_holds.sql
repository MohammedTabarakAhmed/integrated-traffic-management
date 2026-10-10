-- The violation-service account pauses fine payables while an objection is pending (VF-052).

insert into permission (code, service, description_nl, description_en) values
    ('payment.payable.hold', 'payment-service', 'Boetes tijdelijk blokkeren', 'Pause or resume a payable');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('VIOLATION_PAYMENT_HOLDER', 'Boetebetalingen pauzeren', 'Violation payment holder',
     'Pauses and resumes violation fine payables while an objection is pending (VF-052).', true,
     array['payment.payable.hold'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

-- TR-032, UAT-2: when a violation goes to the renter's traffic file, the rental company is notified. The renter is
-- notified by the existing VIOLATION_RECORDED notice (its recipientId is the responsible file, now the renter).
-- Mandatory: TR-032 says the company *shall* be notified. Texts are drafts until the Authority approves them (Q-006).
-- Placeholders use only fields of itms.violation.transferred-to-renter (ViolationService.rentalTransfer).
insert into notification_template (code, version_no, category, mandatory, contents, published_at, published_by,
                                   published_by_username)
values
    ('VIOLATION_TRANSFERRED_TO_RENTER', 1, 'VIOLATION', true, $json$
     {"SMS": {"bodyNl": "سُجّلت المخالفة {{violationNumber}} على المركبة {{plate}} بتاريخ {{occurredAt}} خلال عقد إيجار، وحُوّلت إلى ملف المستأجر.",
              "bodyEn": "Violation {{violationNumber}} for vehicle {{plate}} on {{occurredAt}} was committed during a rental contract and has been transferred to the renter's file."},
      "INBOX": {"subjectNl": "تحويل المخالفة {{violationNumber}} إلى المستأجر",
                "subjectEn": "Violation {{violationNumber}} transferred to the renter",
                "bodyNl": "سُجّلت المخالفة {{violationNumber}} على المركبة {{plate}} بتاريخ {{occurredAt}} خلال عقد الإيجار {{rentalContractId}}، وحُوّلت إلى ملف المستأجر.",
                "bodyEn": "Violation {{violationNumber}} for vehicle {{plate}} on {{occurredAt}} was committed during rental contract {{rentalContractId}} and has been transferred to the renter's file."}}
     $json$::jsonb, now(), 'system:flyway', 'V5__rental_transfer_notifications')
on conflict do nothing;

insert into event_subscription (event_type, template_code, recipient_field, reference_field, active, updated_at)
values
    ('itms.violation.transferred-to-renter', 'VIOLATION_TRANSFERRED_TO_RENTER', 'recipientId', 'violationNumber', true,
     now())
on conflict do nothing;

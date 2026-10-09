-- Violation notices (VF-015, VF-031, VF-032; UAT-1), loaded as data (Q-072 (h), decided by the owner 2026-10-09).
-- The texts are drafts until the Authority approves them (Q-006); a new version is published through the template API.
-- Mandatory: VF-015 says the responsible party *shall* be notified, so a channel opt-out does not stop these notices.
-- Placeholders use only fields that violation-service puts in the events (ViolationService.snapshot / addPoints /
-- suspend); the fine is in minor units, so it is not shown: the customer sees the amount when paying.
insert into notification_template (code, version_no, category, mandatory, contents, published_at, published_by,
                                   published_by_username)
values
    ('VIOLATION_RECORDED', 1, 'VIOLATION', true, $json$
     {"SMS": {"bodyNl": "سُجّلت المخالفة {{violationNumber}} على المركبة {{plate}} بتاريخ {{occurredAt}}. النقاط السوداء: {{points}}.",
              "bodyEn": "Violation {{violationNumber}} was recorded for vehicle {{plate}} on {{occurredAt}}. Black points: {{points}}."},
      "INBOX": {"subjectNl": "مخالفة مرورية {{violationNumber}}",
                "subjectEn": "Traffic violation {{violationNumber}}",
                "bodyNl": "سُجّلت المخالفة {{violationNumber}} على المركبة {{plate}} بتاريخ {{occurredAt}}. النقاط السوداء: {{points}}. يمكنك عرض التفاصيل ودفع الغرامة من حسابك.",
                "bodyEn": "Violation {{violationNumber}} was recorded for vehicle {{plate}} on {{occurredAt}}. Black points: {{points}}. You can view the details and pay the fine from your account."}}
     $json$::jsonb, now(), 'system:flyway', 'V3__violation_notifications'),
    ('BLACK_POINTS_WARNING', 1, 'VIOLATION', true, $json$
     {"SMS": {"bodyNl": "تنبيه: لديك {{activePoints}} نقطة سوداء نشطة. يُوقف الترخيص عند {{suspensionThreshold}} نقطة.",
              "bodyEn": "Warning: you have {{activePoints}} active black points. The licence is suspended at {{suspensionThreshold}} points."},
      "INBOX": {"subjectNl": "تنبيه النقاط السوداء",
                "subjectEn": "Black points warning",
                "bodyNl": "تنبيه: لديك {{activePoints}} نقطة سوداء نشطة بعد المخالفة {{violationNumber}}. يُوقف الترخيص عند {{suspensionThreshold}} نقطة.",
                "bodyEn": "Warning: you have {{activePoints}} active black points after violation {{violationNumber}}. The licence is suspended at {{suspensionThreshold}} points."}}
     $json$::jsonb, now(), 'system:flyway', 'V3__violation_notifications'),
    ('LICENCE_SUSPENDED', 1, 'VIOLATION', true, $json$
     {"SMS": {"bodyNl": "أُوقف ترخيص القيادة لمدة {{months}} أشهر من {{from}} حتى {{until}} لبلوغ {{activePoints}} نقطة سوداء.",
              "bodyEn": "Your driving licence is suspended for {{months}} months, from {{from}} to {{until}}, for reaching {{activePoints}} black points."},
      "INBOX": {"subjectNl": "إيقاف ترخيص القيادة",
                "subjectEn": "Driving licence suspended",
                "bodyNl": "أُوقف ترخيص القيادة لمدة {{months}} أشهر من {{from}} حتى {{until}} لبلوغ {{activePoints}} نقطة سوداء (المخالفة {{violationNumber}}).",
                "bodyEn": "Your driving licence is suspended for {{months}} months, from {{from}} to {{until}}, for reaching {{activePoints}} black points (violation {{violationNumber}})."}}
     $json$::jsonb, now(), 'system:flyway', 'V3__violation_notifications')
on conflict do nothing;

insert into event_subscription (event_type, template_code, recipient_field, reference_field, active, updated_at)
values
    ('itms.violation.recorded', 'VIOLATION_RECORDED', 'recipientId', 'violationNumber', true, now()),
    ('itms.violation.points-warning', 'BLACK_POINTS_WARNING', 'recipientId', 'violationNumber', true, now()),
    ('itms.violation.licence-suspended', 'LICENCE_SUSPENDED', 'recipientId', 'violationNumber', true, now())
on conflict do nothing;

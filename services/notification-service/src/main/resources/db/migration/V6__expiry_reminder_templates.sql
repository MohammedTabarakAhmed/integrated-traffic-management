-- CC-072: expiry reminders for licences, registrations and permits. Watches are registered from the owning services'
-- events (itms.notifications.expiry-events) or through POST /v1/expiry-watches. Placeholders are the values the
-- reminder job sends: subjectType, subjectId, expiryDate, daysLeft. Texts are drafts until the Authority approves them
-- (Q-006). Not mandatory: a customer may switch reminders off in their preferences (CC-073).
insert into notification_template (code, version_no, category, mandatory, contents, published_at, published_by,
                                   published_by_username)
values
    ('EXPIRY_REMINDER_LICENCE', 1, 'EXPIRY_REMINDER', false, $json$
     {"SMS": {"bodyNl": "تنتهي رخصة القيادة {{subjectId}} بتاريخ {{expiryDate}} (بعد {{daysLeft}} يوم). يرجى التجديد قبل الانتهاء.",
              "bodyEn": "Driving licence {{subjectId}} expires on {{expiryDate}} (in {{daysLeft}} days). Please renew it before then."},
      "INBOX": {"subjectNl": "قرب انتهاء رخصة القيادة {{subjectId}}",
                "subjectEn": "Driving licence {{subjectId}} expires soon",
                "bodyNl": "تنتهي رخصة القيادة {{subjectId}} بتاريخ {{expiryDate}} (بعد {{daysLeft}} يوم). يرجى التجديد قبل الانتهاء.",
                "bodyEn": "Driving licence {{subjectId}} expires on {{expiryDate}} (in {{daysLeft}} days). Please renew it before then."}}
     $json$::jsonb, now(), 'system:flyway', 'V6__expiry_reminder_templates'),
    ('EXPIRY_REMINDER_REGISTRATION', 1, 'EXPIRY_REMINDER', false, $json$
     {"SMS": {"bodyNl": "ينتهي تسجيل المركبة {{subjectId}} بتاريخ {{expiryDate}} (بعد {{daysLeft}} يوم). يرجى التجديد قبل الانتهاء.",
              "bodyEn": "The registration of vehicle {{subjectId}} expires on {{expiryDate}} (in {{daysLeft}} days). Please renew it before then."},
      "INBOX": {"subjectNl": "قرب انتهاء تسجيل المركبة {{subjectId}}",
                "subjectEn": "Vehicle registration {{subjectId}} expires soon",
                "bodyNl": "ينتهي تسجيل المركبة {{subjectId}} بتاريخ {{expiryDate}} (بعد {{daysLeft}} يوم). يرجى التجديد قبل الانتهاء.",
                "bodyEn": "The registration of vehicle {{subjectId}} expires on {{expiryDate}} (in {{daysLeft}} days). Please renew it before then."}}
     $json$::jsonb, now(), 'system:flyway', 'V6__expiry_reminder_templates'),
    ('EXPIRY_REMINDER_PERMIT', 1, 'EXPIRY_REMINDER', false, $json$
     {"SMS": {"bodyNl": "ينتهي التصريح {{subjectId}} بتاريخ {{expiryDate}} (بعد {{daysLeft}} يوم).",
              "bodyEn": "Permit {{subjectId}} expires on {{expiryDate}} (in {{daysLeft}} days)."},
      "INBOX": {"subjectNl": "قرب انتهاء التصريح {{subjectId}}",
                "subjectEn": "Permit {{subjectId}} expires soon",
                "bodyNl": "ينتهي التصريح {{subjectId}} بتاريخ {{expiryDate}} (بعد {{daysLeft}} يوم).",
                "bodyEn": "Permit {{subjectId}} expires on {{expiryDate}} (in {{daysLeft}} days)."}}
     $json$::jsonb, now(), 'system:flyway', 'V6__expiry_reminder_templates')
on conflict do nothing;

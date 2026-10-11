-- CC-082; Q-075 f, Q-078 a: bilingual templates of the certificates other modules issue and document-service renders
-- (CertificateHandOff, itms.documents.certificates.events). Placeholders use only fields every such event carries,
-- plus holderNameNl/holderNameEn (traffic file), sourceCertificateNumber (the module's number) and issueDate. Validity is
-- the owning module's (the NOC carries validUntil), so no template validity. Texts are drafts until the Authority
-- approves them (Q-006, Q-067).
insert into certificate_template (code, version_no, title_nl, title_en, body_nl, body_en, validity_days, published_by,
                                  published_by_username, published_at)
values
    ('VL_CERT_OWNERSHIP', 1, 'شهادة ملكية مركبة', 'Vehicle ownership certificate',
     'تشهد الهيئة بأن المركبة ذات رقم الشاسيه {{vin}} مسجلة باسم {{holderNameNl}} (الملف المروري {{ownerFileNumber}}). رقم شهادة المركبة {{sourceCertificateNumber}}، تاريخ الإصدار {{issueDate}}.',
     'The Authority certifies that the vehicle with VIN {{vin}} is registered to {{holderNameEn}} (traffic file {{ownerFileNumber}}). Vehicle certificate number {{sourceCertificateNumber}}, issued on {{issueDate}}.',
     null, 'system:flyway', 'V3__module_certificate_templates', now()),
    ('VL_CERT_NO_OBJECTION', 1, 'شهادة عدم ممانعة للمركبة', 'Vehicle no-objection certificate',
     'لا تمانع الهيئة في المعاملة المطلوبة على المركبة ذات رقم الشاسيه {{vin}} المسجلة باسم {{holderNameNl}}. رقم شهادة المركبة {{sourceCertificateNumber}}، تاريخ الإصدار {{issueDate}}.',
     'The Authority has no objection to the requested transaction on the vehicle with VIN {{vin}} registered to {{holderNameEn}}. Vehicle certificate number {{sourceCertificateNumber}}, issued on {{issueDate}}.',
     null, 'system:flyway', 'V3__module_certificate_templates', now()),
    ('VL_CERT_REGISTRATION_HISTORY', 1, 'شهادة سجل تسجيل مركبة', 'Vehicle registration history certificate',
     'سجل تسجيل المركبة ذات رقم الشاسيه {{vin}} صادر بطلب من {{holderNameNl}}. رقم شهادة المركبة {{sourceCertificateNumber}}، تاريخ الإصدار {{issueDate}}.',
     'Registration history of the vehicle with VIN {{vin}}, issued at the request of {{holderNameEn}}. Vehicle certificate number {{sourceCertificateNumber}}, issued on {{issueDate}}.',
     null, 'system:flyway', 'V3__module_certificate_templates', now()),
    ('VL_CERT_CLEARANCE', 1, 'شهادة براءة ذمة مركبة', 'Vehicle clearance certificate',
     'تشهد الهيئة بأنه لا توجد مستحقات على المركبة ذات رقم الشاسيه {{vin}} المسجلة باسم {{holderNameNl}}. رقم شهادة المركبة {{sourceCertificateNumber}}، تاريخ الإصدار {{issueDate}}.',
     'The Authority certifies that nothing is outstanding on the vehicle with VIN {{vin}} registered to {{holderNameEn}}. Vehicle certificate number {{sourceCertificateNumber}}, issued on {{issueDate}}.',
     null, 'system:flyway', 'V3__module_certificate_templates', now()),
    ('VL_CERT_EXPORT', 1, 'شهادة تصدير مركبة', 'Vehicle export certificate',
     'شُطبت المركبة ذات رقم الشاسيه {{vin}} للتصدير بطلب من {{holderNameNl}}. رقم شهادة المركبة {{sourceCertificateNumber}}، تاريخ الإصدار {{issueDate}}.',
     'The vehicle with VIN {{vin}} has been deregistered for export at the request of {{holderNameEn}}. Vehicle certificate number {{sourceCertificateNumber}}, issued on {{issueDate}}.',
     null, 'system:flyway', 'V3__module_certificate_templates', now()),
    ('VL_CERT_INSURANCE_RECOVERY', 1, 'شهادة استرداد تأمين', 'Insurance recovery certificate',
     'شهادة استرداد تأمين للمركبة ذات رقم الشاسيه {{vin}} المسجلة باسم {{holderNameNl}}. رقم شهادة المركبة {{sourceCertificateNumber}}، تاريخ الإصدار {{issueDate}}.',
     'Insurance recovery certificate for the vehicle with VIN {{vin}} registered to {{holderNameEn}}. Vehicle certificate number {{sourceCertificateNumber}}, issued on {{issueDate}}.',
     null, 'system:flyway', 'V3__module_certificate_templates', now()),
    ('LICENCE_NOC', 1, 'شهادة عدم ممانعة لرخصة قيادة', 'Driving licence no-objection certificate',
     'لا تمانع الهيئة بشأن رخصة القيادة {{licenceNumber}} لحاملها {{holderNameNl}}. رقم الشهادة {{sourceCertificateNumber}}، صالحة حتى {{validUntil}}.',
     'The Authority has no objection regarding driving licence {{licenceNumber}} held by {{holderNameEn}}. Certificate number {{sourceCertificateNumber}}, valid until {{validUntil}}.',
     null, 'system:flyway', 'V3__module_certificate_templates', now())
on conflict do nothing;

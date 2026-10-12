-- examination-service (DL-030..DL-045, DL-036; ADR-065). Learning files, committees, calendars, results and
-- exceptions are scoped by the assignment's regions; settings by the permission. Examination-service's own account
-- holds EXAMINATION_TRANSFER_SENDER (V29). Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('exam.settings.manage', 'examination-service', 'إدارة أنواع الاختبارات وبنك الأسئلة', 'Manage test types, scoring sheets, question bank and training certificate types'),
    ('exam.school.manage',   'examination-service', 'إدارة مدارس تعليم القيادة',           'Manage driving schools, branches, vehicles, instructors and complaints'),
    ('exam.school.read',     'examination-service', 'عرض تقييم مدارس القيادة',             'Read driving school and instructor ratings'),
    ('exam.file.manage',     'examination-service', 'إدارة ملفات التعلم',                  'Open learning files, record training and move them'),
    ('exam.file.read',       'examination-service', 'عرض ملفات التعلم ونتائجها',           'Read learning files, results and transfers'),
    ('exam.committee.manage','examination-service', 'إدارة لجان الاختبار',                 'Create examination committees and their members'),
    ('exam.calendar.manage', 'examination-service', 'إعداد تقويم الاختبارات',              'Build examination calendars (committee heads)'),
    ('exam.calendar.read',   'examination-service', 'عرض تقويم الاختبارات واستخدامه',      'Read examination calendars and their usage'),
    ('exam.booking.manage',  'examination-service', 'حجز مواعيد الاختبار',                 'Book examination slots for learners'),
    ('exam.test.conduct',    'examination-service', 'إجراء الاختبار النظري',               'Deliver theory papers and take the answers'),
    ('exam.result.record',   'examination-service', 'تسجيل نتائج الاختبار العملي',         'Record practical test results (examiners)'),
    ('exam.exception.decide','examination-service', 'مراجعة استثناءات النتائج',            'Decide on flagged examination results (supervisors)');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('EXAM_ADMIN', 'مسؤول الاختبارات', 'Examination administrator',
     'Keeps test types, scoring sheets, the question bank and committees (DL-033, DL-040, DL-043).', true,
     array['exam.settings.manage', 'exam.committee.manage', 'exam.calendar.read'], 1, now(), 'SYSTEM_MIGRATION',
     now(), 'SYSTEM_MIGRATION'),
    ('DRIVING_SCHOOL_OFFICER', 'موظف مدارس القيادة', 'Driving school officer',
     'Registers driving schools, their branches, vehicles and instructors, and complaints (DL-044, DL-045).', true,
     array['exam.school.manage', 'exam.school.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('LEARNING_FILE_OFFICER', 'موظف ملفات التعلم', 'Learning file officer',
     'Opens learning files, records training and books tests (DL-030..DL-032).', true,
     array['exam.file.manage', 'exam.file.read', 'exam.booking.manage', 'exam.calendar.read', 'exam.test.conduct'],
     1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('EXAM_COMMITTEE_HEAD', 'رئيس لجنة الاختبار', 'Examination committee head',
     'Builds the calendar of the committees they head and records results (DL-041, DL-034).', true,
     array['exam.calendar.manage', 'exam.calendar.read', 'exam.result.record', 'exam.file.read'], 1, now(),
     'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('EXAMINER', 'فاحص', 'Examiner',
     'Records the practical results of the bookings assigned to them (DL-034, DL-035).', true,
     array['exam.result.record', 'exam.file.read'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION'),
    ('EXAM_SUPERVISOR', 'مشرف الاختبارات', 'Examination supervisor',
     'Decides on flagged results and reads calendars and ratings (DL-037, DL-042, DL-045).', true,
     array['exam.exception.decide', 'exam.file.read', 'exam.calendar.read', 'exam.school.read'], 1, now(),
     'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');

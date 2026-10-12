-- examination-service schema (DL-030..DL-045, DL-036; ADR-065). Owned exclusively by this service. Results, papers,
-- transfers and status history are append-only facts; settings are versioned by their audit records.

-- DL-044: driving schools, branches, vehicles, instructors and training certificate types.
create table driving_school (
    id uuid primary key,
    code varchar(40) not null unique check (code ~ '^[A-Z0-9_]{1,40}$'),
    name_nl varchar(200) not null,
    name_en varchar(200) not null,
    region varchar(40) not null check (region ~ '^[A-Z0-9_]{1,40}$'),
    status varchar(10) not null check (status in ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at timestamptz not null,
    created_by varchar(100) not null
);
create table school_branch (
    id uuid primary key,
    school_id uuid not null references driving_school (id),
    code varchar(40) not null check (code ~ '^[A-Z0-9_]{1,40}$'),
    address varchar(500) not null,
    region varchar(40) not null,
    unique (school_id, code)
);
create table school_vehicle (
    id uuid primary key,
    school_id uuid not null references driving_school (id),
    plate varchar(40) not null,
    class_code varchar(40) not null,
    dual_controls boolean not null,
    unique (school_id, plate)
);
create table instructor (
    id uuid primary key,
    school_id uuid not null references driving_school (id),
    user_id varchar(100),
    traffic_file_number varchar(40) not null,
    grade varchar(40) not null check (grade ~ '^[A-Z0-9_]{1,40}$'),
    classes varchar(40)[] not null,
    status varchar(10) not null check (status in ('ACTIVE', 'SUSPENDED', 'LEFT')),
    unique (school_id, traffic_file_number)
);
create table certificate_type (
    code varchar(40) primary key check (code ~ '^[A-Z0-9_]{1,40}$'),
    name_nl varchar(200) not null,
    name_en varchar(200) not null,
    hours integer not null check (hours between 1 and 500)
);
create table school_complaint (
    id uuid primary key,
    school_id uuid not null references driving_school (id),
    instructor_id uuid references instructor (id),
    summary varchar(1000) not null,
    received_at timestamptz not null,
    recorded_by varchar(100) not null
);

-- DL-043: test types with scoring sheet, pass mark, retest wait and fee code.
create table test_type (
    code varchar(40) primary key check (code ~ '^[A-Z0-9_]{1,40}$'),
    kind varchar(10) not null check (kind in ('THEORY', 'YARD', 'ROAD')),
    name_nl varchar(200) not null,
    name_en varchar(200) not null,
    max_score integer not null check (max_score > 0),
    pass_mark integer not null check (pass_mark between 1 and max_score),
    retest_wait_days integer not null check (retest_wait_days >= 0),
    fee_service_code varchar(40),
    question_count integer not null check (question_count >= 0),
    sheet jsonb not null,
    active boolean not null,
    updated_at timestamptz not null,
    updated_by varchar(100) not null
);

-- DL-030..DL-032: learning files.
create sequence learning_file_seq;
create table learning_file (
    id uuid primary key,
    number varchar(30) not null unique,
    traffic_file_number varchar(40) not null,
    class_code varchar(40) not null,
    gearbox varchar(10) not null check (gearbox in ('MANUAL', 'AUTOMATIC')),
    region varchar(40) not null check (region ~ '^[A-Z0-9_]{1,40}$'),
    school_id uuid references driving_school (id),
    instructor_id uuid references instructor (id),
    status varchar(16) not null check (status in ('OPEN', 'IN_TRAINING', 'READY_FOR_TEST', 'PASSED', 'FAILED',
                                                  'EXPIRED', 'CANCELLED')),
    failed_attempts integer not null default 0 check (failed_attempts >= 0),
    extra_hours_required integer not null default 0 check (extra_hours_required >= 0),
    training_hours integer not null default 0 check (training_hours >= 0),
    opened_at timestamptz not null,
    last_progress_at timestamptz not null,
    version bigint not null check (version >= 1)
);
-- One live file per person and class.
create unique index uq_learning_file_live on learning_file (traffic_file_number, class_code)
    where status not in ('PASSED', 'EXPIRED', 'CANCELLED');
create index ix_learning_file_progress on learning_file (last_progress_at)
    where status not in ('PASSED', 'EXPIRED', 'CANCELLED');
create table learning_file_history (
    id bigint generated always as identity primary key,
    file_id uuid not null references learning_file (id),
    status varchar(16) not null,
    reason varchar(500),
    changed_at timestamptz not null,
    changed_by varchar(100) not null
);
create table training_session (
    id uuid primary key,
    file_id uuid not null references learning_file (id),
    instructor_id uuid references instructor (id),
    hours integer not null check (hours between 1 and 12),
    certificate_type varchar(40) references certificate_type (code),
    held_on date not null,
    recorded_at timestamptz not null,
    recorded_by varchar(100) not null
);

-- DL-040..DL-042: committees and the examination calendar.
create table committee (
    id uuid primary key,
    code varchar(40) not null unique check (code ~ '^[A-Z0-9_]{1,40}$'),
    location varchar(80) not null,
    region varchar(40) not null,
    valid_from date not null,
    valid_to date not null check (valid_to >= valid_from),
    created_at timestamptz not null,
    created_by varchar(100) not null
);
create table committee_member (
    committee_id uuid not null references committee (id),
    user_id varchar(100) not null,
    role varchar(10) not null check (role in ('HEAD', 'EXAMINER', 'OBSERVER')),
    primary key (committee_id, user_id)
);
create table exam_slot (
    id uuid primary key,
    committee_id uuid not null references committee (id),
    location varchar(80) not null,
    class_code varchar(40) not null,
    test_type_code varchar(40) not null references test_type (code),
    starts_at timestamptz not null,
    capacity integer not null check (capacity between 1 and 500),
    booked integer not null default 0 check (booked between 0 and capacity),
    created_by varchar(100) not null,
    unique (committee_id, test_type_code, class_code, starts_at)
);
create index ix_exam_slot_location_time on exam_slot (location, starts_at);
create table booking (
    id uuid primary key,
    slot_id uuid not null references exam_slot (id),
    file_id uuid not null references learning_file (id),
    test_type_code varchar(40) not null,
    examiner_id varchar(100),
    status varchar(10) not null check (status in ('BOOKED', 'SAT', 'CANCELLED', 'NO_SHOW')),
    paper_seed bigint,
    booked_at timestamptz not null,
    booked_by varchar(100) not null
);
create unique index uq_booking_open on booking (file_id, test_type_code) where status = 'BOOKED';

-- DL-033: question bank, multilingual and weighted.
create table question (
    id uuid primary key,
    test_type_code varchar(40) not null references test_type (code),
    language varchar(5) not null check (language ~ '^[a-z]{2}$'),
    category varchar(40) not null,
    weight integer not null check (weight between 1 and 100),
    text varchar(2000) not null,
    options jsonb not null,
    correct_option integer not null check (correct_option >= 0),
    active boolean not null,
    created_at timestamptz not null,
    created_by varchar(100) not null
);
create index ix_question_bank on question (test_type_code, language) where active;

-- DL-033, DL-034, DL-038: results. A result is a fact; offline uploads are idempotent by client result id.
create table test_result (
    id uuid primary key,
    booking_id uuid not null unique references booking (id),
    file_id uuid not null references learning_file (id),
    test_type_code varchar(40) not null,
    examiner_id varchar(100),
    client_result_id varchar(80) unique,
    score integer not null,
    passed boolean not null,
    faults varchar(40)[] not null default '{}',
    answers jsonb,
    route jsonb,
    video_document_id uuid,
    recorded_at timestamptz not null,
    received_at timestamptz not null,
    first_attempt boolean not null
);
create index ix_test_result_file on test_result (file_id);

-- DL-037: exceptions for supervisor review.
create table result_exception (
    id uuid primary key,
    result_id uuid not null references test_result (id),
    reason varchar(40) not null,
    status varchar(10) not null check (status in ('OPEN', 'ACCEPTED', 'REJECTED')),
    raised_at timestamptz not null,
    decided_at timestamptz,
    decided_by varchar(100),
    decision_reason varchar(500),
    unique (result_id, reason)
);

-- DL-036: hand-off of a passed file to licensing; retried until licensing accepts it.
create table licensing_transfer (
    file_id uuid primary key references learning_file (id),
    status varchar(10) not null check (status in ('PENDING', 'SENT')),
    attempts integer not null default 0,
    last_error varchar(200),
    created_at timestamptz not null,
    sent_at timestamptz,
    licensing_transfer_id varchar(60)
);

create function examination_fact_guard() returns trigger language plpgsql as $$
begin
    raise exception 'examination results and history are append-only (DL-034, CC-122)';
end
$$;
create trigger trg_test_result_guard before update or delete on test_result
    for each row execute function examination_fact_guard();
create trigger trg_learning_file_history_guard before update or delete on learning_file_history
    for each row execute function examination_fact_guard();

package gov.itms.examination.infrastructure;

import gov.itms.examination.application.Ports;
import gov.itms.examination.domain.Exams;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Examination data in examination-service's own database (V1). */
class JdbcExamRepository implements Ports.Repository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ZoneId zone;

    JdbcExamRepository(JdbcClient jdbc, ObjectMapper json, ZoneId zone) {
        this.jdbc = jdbc;
        this.json = json;
        this.zone = zone;
    }

    // ------------------------------------------------------------------ settings, schools

    @Override
    public void saveTestType(Exams.TestType t, String actor, Instant at) {
        jdbc.sql("""
                        insert into test_type (code, kind, name_nl, name_en, max_score, pass_mark, retest_wait_days,
                            fee_service_code, question_count, sheet, active, updated_at, updated_by)
                        values (:code, :kind, :nl, :en, :max, :pass, :wait, :fee, :q, cast(:sheet as jsonb), :active,
                            :at, :by)
                        on conflict (code) do update set kind = excluded.kind, name_nl = excluded.name_nl,
                            name_en = excluded.name_en, max_score = excluded.max_score, pass_mark = excluded.pass_mark,
                            retest_wait_days = excluded.retest_wait_days, fee_service_code = excluded.fee_service_code,
                            question_count = excluded.question_count, sheet = excluded.sheet, active = excluded.active,
                            updated_at = excluded.updated_at, updated_by = excluded.updated_by""")
                .param("code", t.code()).param("kind", t.kind().name()).param("nl", t.nameNl()).param("en", t.nameEn())
                .param("max", t.maxScore()).param("pass", t.passMark()).param("wait", t.retestWaitDays())
                .param("fee", t.feeServiceCode()).param("q", t.questionCount())
                .param("sheet", json.writeValueAsString(t.sheet())).param("active", t.active())
                .param("at", ts(at)).param("by", actor).update();
    }

    @Override
    public Optional<Exams.TestType> testType(String code) {
        return jdbc.sql("select * from test_type where code = :code").param("code", code)
                .query((rs, n) -> new Exams.TestType(rs.getString("code"), Exams.TestKind.valueOf(rs.getString("kind")),
                        rs.getString("name_nl"), rs.getString("name_en"), rs.getInt("max_score"),
                        rs.getInt("pass_mark"), rs.getInt("retest_wait_days"), rs.getString("fee_service_code"),
                        rs.getInt("question_count"), json.readValue(rs.getString("sheet"),
                        new TypeReference<List<Exams.SheetItem>>() { }), rs.getBoolean("active")))
                .optional();
    }

    @Override
    public void saveCertificateType(Exams.CertificateType t) {
        jdbc.sql("""
                        insert into certificate_type (code, name_nl, name_en, hours) values (:c, :nl, :en, :h)
                        on conflict (code) do update set name_nl = excluded.name_nl, name_en = excluded.name_en,
                            hours = excluded.hours""")
                .param("c", t.code()).param("nl", t.nameNl()).param("en", t.nameEn()).param("h", t.hours()).update();
    }

    @Override
    public Optional<Exams.CertificateType> certificateType(String code) {
        return jdbc.sql("select * from certificate_type where code = :c").param("c", code)
                .query((rs, n) -> new Exams.CertificateType(rs.getString("code"), rs.getString("name_nl"),
                        rs.getString("name_en"), rs.getInt("hours"))).optional();
    }

    @Override
    public void insertSchool(Exams.School s, String actor, Instant at) {
        jdbc.sql("""
                        insert into driving_school (id, code, name_nl, name_en, region, status, created_at, created_by)
                        values (:id, :code, :nl, :en, :region, :status, :at, :by)""")
                .param("id", s.id()).param("code", s.code()).param("nl", s.nameNl()).param("en", s.nameEn())
                .param("region", s.region()).param("status", s.status()).param("at", ts(at)).param("by", actor)
                .update();
    }

    @Override
    public Optional<Exams.School> school(UUID id) {
        return jdbc.sql("select * from driving_school where id = :id").param("id", id)
                .query((rs, n) -> new Exams.School(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name_nl"), rs.getString("name_en"), rs.getString("region"),
                        rs.getString("status"))).optional();
    }

    @Override
    public void insertBranch(Exams.Branch b) {
        jdbc.sql("insert into school_branch (id, school_id, code, address, region) values (:id, :s, :c, :a, :r)")
                .param("id", b.id()).param("s", b.schoolId()).param("c", b.code()).param("a", b.address())
                .param("r", b.region()).update();
    }

    @Override
    public void insertVehicle(Exams.SchoolVehicle v) {
        jdbc.sql("""
                        insert into school_vehicle (id, school_id, plate, class_code, dual_controls)
                        values (:id, :s, :p, :c, :d)""")
                .param("id", v.id()).param("s", v.schoolId()).param("p", v.plate()).param("c", v.classCode())
                .param("d", v.dualControls()).update();
    }

    @Override
    public void insertInstructor(Exams.Instructor i) {
        jdbc.sql("""
                        insert into instructor (id, school_id, user_id, traffic_file_number, grade, classes, status)
                        values (:id, :s, :u, :f, :g, string_to_array(:classes, ','), :st)""")
                .param("id", i.id()).param("s", i.schoolId()).param("u", i.userId()).param("f", i.trafficFileNumber())
                .param("g", i.grade()).param("classes", String.join(",", i.classes())).param("st", i.status())
                .update();
    }

    @Override
    public Optional<Exams.Instructor> instructor(UUID id) {
        return jdbc.sql("select * from instructor where id = :id").param("id", id)
                .query((rs, n) -> new Exams.Instructor(rs.getObject("id", UUID.class),
                        rs.getObject("school_id", UUID.class), rs.getString("user_id"),
                        rs.getString("traffic_file_number"), rs.getString("grade"),
                        new LinkedHashSet<>(strings(rs.getArray("classes"))), rs.getString("status"))).optional();
    }

    @Override
    public void insertComplaint(UUID id, UUID schoolId, UUID instructorId, String summary, Instant at, String actor) {
        jdbc.sql("""
                        insert into school_complaint (id, school_id, instructor_id, summary, received_at, recorded_by)
                        values (:id, :s, :i, :sum, :at, :by)""")
                .param("id", id).param("s", schoolId).param("i", instructorId).param("sum", summary)
                .param("at", ts(at)).param("by", actor).update();
    }

    @Override
    public Exams.Rating rating(UUID schoolId, UUID instructorId) {
        String who = instructorId == null ? "f.school_id = :s" : "f.instructor_id = :i";
        Map<String, Object> row = jdbc.sql("""
                        select count(distinct f.id) as learners,
                               count(r.id) filter (where r.first_attempt) as first_attempts,
                               count(r.id) filter (where r.first_attempt and r.passed) as first_passes
                        from learning_file f left join test_result r on r.file_id = f.id
                        where\s""" + who)
                .param("s", schoolId).param("i", instructorId).query().singleRow();
        long complaints = jdbc.sql("select count(*) from school_complaint where school_id = :s"
                        + (instructorId == null ? "" : " and instructor_id = :i"))
                .param("s", schoolId).param("i", instructorId).query(Long.class).single();
        return Exams.Rating.of(instructorId == null ? schoolId : instructorId, num(row.get("learners")),
                num(row.get("first_attempts")), num(row.get("first_passes")), (int) complaints);
    }

    // ------------------------------------------------------------------ learning files

    @Override
    public String nextFileNumber(int year) {
        long n = jdbc.sql("select nextval('learning_file_seq')").query(Long.class).single();
        return "LF-" + year + "-" + String.format(Locale.ROOT, "%08d", n);
    }

    @Override
    public void insertFile(Exams.LearningFile f, String actor) {
        jdbc.sql("""
                        insert into learning_file (id, number, traffic_file_number, class_code, gearbox, region,
                            school_id, instructor_id, status, failed_attempts, extra_hours_required, training_hours,
                            opened_at, last_progress_at, version)
                        values (:id, :num, :tf, :cls, :gear, :region, :school, :instr, :status, 0, 0, 0, :at, :at, 1)""")
                .param("id", f.id()).param("num", f.number()).param("tf", f.trafficFileNumber())
                .param("cls", f.classCode()).param("gear", f.gearbox()).param("region", f.region())
                .param("school", f.schoolId()).param("instr", f.instructorId()).param("status", f.status().name())
                .param("at", ts(f.openedAt())).update();
        history(f.id(), f.status().name(), null, f.openedAt(), actor);
    }

    @Override
    public Optional<Exams.LearningFile> file(UUID id) {
        return jdbc.sql("select * from learning_file where id = :id").param("id", id).query(this::mapFile).optional();
    }

    @Override
    public Optional<Exams.LearningFile> fileForUpdate(UUID id) {
        return jdbc.sql("select * from learning_file where id = :id for update").param("id", id).query(this::mapFile)
                .optional();
    }

    @Override
    public void updateFile(Exams.LearningFile f, String reason, String actor, Instant at, boolean statusChanged) {
        int n = jdbc.sql("""
                        update learning_file set status = :status, failed_attempts = :failed,
                            extra_hours_required = :extra, training_hours = :hours, last_progress_at = :progress,
                            version = :version
                        where id = :id and version = :version - 1""")
                .param("status", f.status().name()).param("failed", f.failedAttempts())
                .param("extra", f.extraHoursRequired()).param("hours", f.trainingHours())
                .param("progress", ts(f.lastProgressAt())).param("version", f.version()).param("id", f.id()).update();
        if (n != 1) {
            throw new org.springframework.dao.OptimisticLockingFailureException("learning file changed");
        }
        if (statusChanged) {
            history(f.id(), f.status().name(), reason, at, actor);
        }
    }

    @Override
    public void insertTraining(UUID id, UUID fileId, UUID instructorId, int hours, String certificateType,
                               LocalDate heldOn, Instant at, String actor) {
        jdbc.sql("""
                        insert into training_session (id, file_id, instructor_id, hours, certificate_type, held_on,
                            recorded_at, recorded_by) values (:id, :f, :i, :h, :c, :d, :at, :by)""")
                .param("id", id).param("f", fileId).param("i", instructorId).param("h", hours)
                .param("c", certificateType).param("d", java.sql.Date.valueOf(heldOn)).param("at", ts(at))
                .param("by", actor).update();
    }

    @Override
    public List<Exams.LearningFile> stale(Instant before, int limit) {
        return jdbc.sql("""
                        select * from learning_file
                        where status not in ('PASSED', 'EXPIRED', 'CANCELLED') and last_progress_at < :before
                        order by last_progress_at limit :limit for update skip locked""")
                .param("before", ts(before)).param("limit", limit).query(this::mapFile).list();
    }

    // ------------------------------------------------------------------ committees, calendar

    @Override
    public void insertCommittee(Exams.Committee c, String actor, Instant at) {
        jdbc.sql("""
                        insert into committee (id, code, location, region, valid_from, valid_to, created_at, created_by)
                        values (:id, :code, :loc, :region, :from, :to, :at, :by)""")
                .param("id", c.id()).param("code", c.code()).param("loc", c.location()).param("region", c.region())
                .param("from", java.sql.Date.valueOf(c.validFrom())).param("to", java.sql.Date.valueOf(c.validTo()))
                .param("at", ts(at)).param("by", actor).update();
        for (Exams.Member m : c.members()) {
            jdbc.sql("insert into committee_member (committee_id, user_id, role) values (:c, :u, :r)")
                    .param("c", c.id()).param("u", m.userId()).param("r", m.role().name()).update();
        }
    }

    @Override
    public Optional<Exams.Committee> committee(UUID id) {
        List<Exams.Member> members = jdbc.sql("select user_id, role from committee_member where committee_id = :c "
                        + "order by user_id").param("c", id)
                .query((rs, n) -> new Exams.Member(rs.getString("user_id"),
                        Exams.MemberRole.valueOf(rs.getString("role")))).list();
        return jdbc.sql("select * from committee where id = :id").param("id", id)
                .query((rs, n) -> new Exams.Committee(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("location"), rs.getString("region"), rs.getDate("valid_from").toLocalDate(),
                        rs.getDate("valid_to").toLocalDate(), members)).optional();
    }

    @Override
    public void insertSlot(Exams.Slot s, String actor) {
        jdbc.sql("""
                        insert into exam_slot (id, committee_id, location, class_code, test_type_code, starts_at,
                            capacity, booked, created_by) values (:id, :c, :loc, :cls, :tt, :at, :cap, 0, :by)""")
                .param("id", s.id()).param("c", s.committeeId()).param("loc", s.location()).param("cls", s.classCode())
                .param("tt", s.testTypeCode()).param("at", ts(s.startsAt())).param("cap", s.capacity())
                .param("by", actor).update();
    }

    @Override
    public Optional<Exams.Slot> slotForUpdate(UUID id) {
        return jdbc.sql("select * from exam_slot where id = :id for update").param("id", id)
                .query((rs, n) -> new Exams.Slot(rs.getObject("id", UUID.class),
                        rs.getObject("committee_id", UUID.class), rs.getString("location"),
                        rs.getString("class_code"), rs.getString("test_type_code"),
                        rs.getTimestamp("starts_at").toInstant(), rs.getInt("capacity"), rs.getInt("booked")))
                .optional();
    }

    @Override
    public List<Ports.SlotUsage> usage(String location, Instant from, Instant to) {
        return jdbc.sql("""
                        select s.id, s.location, s.class_code, s.test_type_code, s.starts_at, s.capacity, s.booked,
                               count(b.id) filter (where b.status = 'SAT') as sat,
                               count(b.id) filter (where b.status = 'NO_SHOW') as no_shows
                        from exam_slot s left join booking b on b.slot_id = s.id
                        where s.location = :loc and s.starts_at >= :from and s.starts_at < :to
                        group by s.id order by s.starts_at""")
                .param("loc", location).param("from", ts(from)).param("to", ts(to))
                .query((rs, n) -> new Ports.SlotUsage(rs.getObject("id", UUID.class), rs.getString("location"),
                        rs.getString("class_code"), rs.getString("test_type_code"),
                        rs.getTimestamp("starts_at").toInstant(), rs.getInt("capacity"), rs.getInt("booked"),
                        rs.getInt("sat"), rs.getInt("no_shows"))).list();
    }

    // ------------------------------------------------------------------ bookings, papers, results

    @Override
    public void insertBooking(Exams.Booking b, Long seed, String actor) {
        jdbc.sql("""
                        insert into booking (id, slot_id, file_id, test_type_code, examiner_id, status, paper_seed,
                            booked_at, booked_by) values (:id, :slot, :file, :tt, :ex, 'BOOKED', :seed, :at, :by)""")
                .param("id", b.id()).param("slot", b.slotId()).param("file", b.fileId())
                .param("tt", b.testTypeCode()).param("ex", b.examinerId()).param("seed", seed)
                .param("at", ts(b.bookedAt())).param("by", actor).update();
        jdbc.sql("update exam_slot set booked = booked + 1 where id = :id").param("id", b.slotId()).update();
    }

    @Override
    public Optional<Exams.Booking> booking(UUID id) {
        return jdbc.sql("select * from booking where id = :id").param("id", id)
                .query((rs, n) -> new Exams.Booking(rs.getObject("id", UUID.class), rs.getObject("slot_id", UUID.class),
                        rs.getObject("file_id", UUID.class), rs.getString("test_type_code"),
                        rs.getString("examiner_id"), rs.getString("status"), rs.getTimestamp("booked_at").toInstant()))
                .optional();
    }

    @Override
    public Optional<Long> paperSeed(UUID bookingId) {
        return jdbc.sql("select paper_seed from booking where id = :id and paper_seed is not null")
                .param("id", bookingId).query(Long.class).optional();
    }

    @Override
    public void markBooking(UUID id, String status) {
        jdbc.sql("update booking set status = :s where id = :id").param("s", status).param("id", id).update();
    }

    @Override
    public Map<String, Integer> pairings(List<String> examiners, UUID instructorId) {
        Map<String, Integer> out = new HashMap<>();
        if (instructorId == null || examiners.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        select b.examiner_id, count(*) as n from booking b join learning_file f on f.id = b.file_id
                        where f.instructor_id = :i and b.examiner_id in (:ex) group by b.examiner_id""")
                .param("i", instructorId).param("ex", examiners)
                .query((rs, n) -> out.put(rs.getString("examiner_id"), rs.getInt("n"))).list();
        return out;
    }

    @Override
    public Map<String, Integer> load(List<String> examiners, LocalDate day) {
        Map<String, Integer> out = new HashMap<>();
        if (examiners.isEmpty()) {
            return out;
        }
        Instant from = day.atStartOfDay(zone).toInstant();
        jdbc.sql("""
                        select b.examiner_id, count(*) as n from booking b join exam_slot s on s.id = b.slot_id
                        where b.examiner_id in (:ex) and s.starts_at >= :from and s.starts_at < :to
                          and b.status <> 'CANCELLED' group by b.examiner_id""")
                .param("ex", examiners).param("from", ts(from)).param("to", ts(from.plusSeconds(86_400)))
                .query((rs, n) -> out.put(rs.getString("examiner_id"), rs.getInt("n"))).list();
        return out;
    }

    @Override
    public Optional<Instant> lastFailure(UUID fileId, String testTypeCode) {
        return jdbc.sql("""
                        select recorded_at from test_result
                        where file_id = :f and test_type_code = :tt and not passed
                        order by recorded_at desc limit 1""")
                .param("f", fileId).param("tt", testTypeCode)
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).optional();
    }

    @Override
    public List<Exams.Question> bank(String testTypeCode, String language) {
        return jdbc.sql("""
                        select * from question where test_type_code = :tt and language = :lang and active
                        order by id""")
                .param("tt", testTypeCode).param("lang", language)
                .query((rs, n) -> new Exams.Question(rs.getObject("id", UUID.class), rs.getString("test_type_code"),
                        rs.getString("language"), rs.getString("category"), rs.getInt("weight"), rs.getString("text"),
                        json.readValue(rs.getString("options"), new TypeReference<List<String>>() { }),
                        rs.getInt("correct_option"), rs.getBoolean("active"))).list();
    }

    @Override
    public void insertQuestion(Exams.Question q, String actor, Instant at) {
        jdbc.sql("""
                        insert into question (id, test_type_code, language, category, weight, text, options,
                            correct_option, active, created_at, created_by)
                        values (:id, :tt, :lang, :cat, :w, :text, cast(:opts as jsonb), :correct, true, :at, :by)""")
                .param("id", q.id()).param("tt", q.testTypeCode()).param("lang", q.language())
                .param("cat", q.category()).param("w", q.weight()).param("text", q.text())
                .param("opts", json.writeValueAsString(q.options())).param("correct", q.correctOption())
                .param("at", ts(at)).param("by", actor).update();
    }

    @Override
    public Optional<Ports.Result> resultByClientId(String clientResultId) {
        if (clientResultId == null) {
            return Optional.empty();
        }
        return jdbc.sql(RESULT + " where r.client_result_id = :c").param("c", clientResultId).query(this::mapResult)
                .optional();
    }

    @Override
    public boolean anyResult(UUID fileId, String testTypeCode) {
        return jdbc.sql("select count(*) from test_result where file_id = :f and test_type_code = :tt")
                .param("f", fileId).param("tt", testTypeCode).query(Long.class).single() > 0;
    }

    @Override
    public void insertResult(Ports.Result r, Map<UUID, Integer> answers, List<Exams.RoutePoint> route,
                             UUID videoDocumentId, Instant receivedAt) {
        jdbc.sql("""
                        insert into test_result (id, booking_id, file_id, test_type_code, examiner_id,
                            client_result_id, score, passed, faults, answers, route, video_document_id, recorded_at,
                            received_at, first_attempt)
                        values (:id, :b, :f, :tt, :ex, :c, :score, :passed, string_to_array(:faults, ','),
                            cast(:answers as jsonb), cast(:route as jsonb), :video, :rec, :recv, :first)""")
                .param("id", r.id()).param("b", r.bookingId()).param("f", r.fileId()).param("tt", r.testTypeCode())
                .param("ex", r.examinerId()).param("c", r.clientResultId()).param("score", r.score())
                .param("passed", r.passed()).param("faults", String.join(",", r.faults()))
                .param("answers", answers == null ? null : json.writeValueAsString(answers))
                .param("route", route == null || route.isEmpty() ? null : json.writeValueAsString(route))
                .param("video", videoDocumentId).param("rec", ts(r.recordedAt())).param("recv", ts(receivedAt))
                .param("first", r.firstAttempt()).update();
    }

    @Override
    public void insertException(UUID id, UUID resultId, String reason, Instant at) {
        jdbc.sql("""
                        insert into result_exception (id, result_id, reason, status, raised_at)
                        values (:id, :r, :reason, 'OPEN', :at)""")
                .param("id", id).param("r", resultId).param("reason", reason).param("at", ts(at)).update();
    }

    @Override
    public Optional<Ports.ExceptionItem> exceptionForUpdate(UUID id) {
        return jdbc.sql("select * from result_exception where id = :id for update").param("id", id)
                .query(this::mapException).optional();
    }

    @Override
    public void decideException(UUID id, String status, String actor, String reason, Instant at) {
        jdbc.sql("""
                        update result_exception set status = :s, decided_by = :by, decision_reason = :reason,
                            decided_at = :at where id = :id and status = 'OPEN'""")
                .param("s", status).param("by", actor).param("reason", reason).param("at", ts(at)).param("id", id)
                .update();
    }

    @Override
    public List<Ports.ExceptionItem> openExceptions(String region, int limit) {
        return jdbc.sql("""
                        select e.* from result_exception e join test_result r on r.id = e.result_id
                        join learning_file f on f.id = r.file_id
                        where e.status = 'OPEN' and f.region = :region order by e.raised_at limit :limit""")
                .param("region", region).param("limit", limit).query(this::mapException).list();
    }

    @Override
    public List<String> cleanPasses(UUID fileId) {
        return jdbc.sql("""
                        select distinct r.test_type_code from test_result r
                        where r.file_id = :f and r.passed and not exists (
                            select 1 from result_exception e where e.result_id = r.id and e.status <> 'ACCEPTED')""")
                .param("f", fileId).query(String.class).list();
    }

    @Override
    public List<Ports.Result> results(UUID fileId) {
        return jdbc.sql(RESULT + " where r.file_id = :f order by r.recorded_at, r.id").param("f", fileId)
                .query(this::mapResult).list();
    }

    @Override
    public UUID resultFile(UUID resultId) {
        return jdbc.sql("select file_id from test_result where id = :id").param("id", resultId).query(UUID.class)
                .single();
    }

    // ------------------------------------------------------------------ transfers

    @Override
    public void insertTransfer(UUID fileId, Instant at) {
        jdbc.sql("""
                        insert into licensing_transfer (file_id, status, created_at) values (:f, 'PENDING', :at)
                        on conflict (file_id) do nothing""")
                .param("f", fileId).param("at", ts(at)).update();
    }

    @Override
    public List<Ports.Transfer> pendingTransfers(int limit) {
        return jdbc.sql("select * from licensing_transfer where status = 'PENDING' order by created_at limit :l")
                .param("l", limit).query(this::mapTransfer).list();
    }

    @Override
    public void transferSent(UUID fileId, String licensingTransferId, Instant at) {
        jdbc.sql("""
                        update licensing_transfer set status = 'SENT', sent_at = :at, licensing_transfer_id = :id,
                            attempts = attempts + 1, last_error = null where file_id = :f""")
                .param("at", ts(at)).param("id", licensingTransferId).param("f", fileId).update();
    }

    @Override
    public void transferFailed(UUID fileId, String error) {
        jdbc.sql("update licensing_transfer set attempts = attempts + 1, last_error = :e where file_id = :f")
                .param("e", error).param("f", fileId).update();
    }

    @Override
    public Optional<Ports.Transfer> transfer(UUID fileId) {
        return jdbc.sql("select * from licensing_transfer where file_id = :f").param("f", fileId)
                .query(this::mapTransfer).optional();
    }

    // ------------------------------------------------------------------ mapping

    private static final String RESULT = """
            select r.*, coalesce((select array_agg(e.reason order by e.reason) from result_exception e
                                  where e.result_id = r.id), '{}') as exception_reasons
            from test_result r""";

    private void history(UUID fileId, String status, String reason, Instant at, String actor) {
        jdbc.sql("""
                        insert into learning_file_history (file_id, status, reason, changed_at, changed_by)
                        values (:f, :s, :r, :at, :by)""")
                .param("f", fileId).param("s", status).param("r", reason).param("at", ts(at)).param("by", actor)
                .update();
    }

    private Exams.LearningFile mapFile(ResultSet rs, int row) throws SQLException {
        return new Exams.LearningFile(rs.getObject("id", UUID.class), rs.getString("number"),
                rs.getString("traffic_file_number"), rs.getString("class_code"), rs.getString("gearbox"),
                rs.getString("region"), rs.getObject("school_id", UUID.class),
                rs.getObject("instructor_id", UUID.class), Exams.FileStatus.valueOf(rs.getString("status")),
                rs.getInt("failed_attempts"), rs.getInt("extra_hours_required"), rs.getInt("training_hours"),
                rs.getTimestamp("opened_at").toInstant(), rs.getTimestamp("last_progress_at").toInstant(),
                rs.getLong("version"));
    }

    private Ports.Result mapResult(ResultSet rs, int row) throws SQLException {
        return new Ports.Result(rs.getObject("id", UUID.class), rs.getObject("booking_id", UUID.class),
                rs.getObject("file_id", UUID.class), rs.getString("test_type_code"), rs.getString("examiner_id"),
                rs.getString("client_result_id"), rs.getInt("score"), rs.getBoolean("passed"),
                strings(rs.getArray("faults")), rs.getTimestamp("recorded_at").toInstant(),
                rs.getBoolean("first_attempt"), strings(rs.getArray("exception_reasons")));
    }

    private Ports.ExceptionItem mapException(ResultSet rs, int row) throws SQLException {
        return new Ports.ExceptionItem(rs.getObject("id", UUID.class), rs.getObject("result_id", UUID.class),
                rs.getString("reason"), rs.getString("status"), rs.getTimestamp("raised_at").toInstant(),
                rs.getString("decided_by"), rs.getString("decision_reason"));
    }

    private Ports.Transfer mapTransfer(ResultSet rs, int row) throws SQLException {
        return new Ports.Transfer(rs.getObject("file_id", UUID.class), rs.getString("status"),
                rs.getInt("attempts"), rs.getString("licensing_transfer_id"));
    }

    private static List<String> strings(Array a) throws SQLException {
        if (a == null) {
            return List.of();
        }
        return Arrays.stream((String[]) a.getArray()).filter(s -> s != null && !s.isEmpty()).toList();
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }
}

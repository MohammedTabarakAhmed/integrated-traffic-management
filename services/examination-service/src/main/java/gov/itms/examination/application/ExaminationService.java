package gov.itms.examination.application;

import gov.itms.examination.domain.DomainException;
import gov.itms.examination.domain.Exams;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Examination use cases (DL-030..DL-045, DL-036; ADR-065). Learning files, committees, slots and results are scoped
 * by region (CC-001); settings and schools by the permission alone. Every change is audited; status changes and
 * passes are published (no personal data beyond the file numbers).
 */
public class ExaminationService {

    public static final String SETTINGS = "exam.settings.manage";
    public static final String SCHOOLS = "exam.school.manage";
    public static final String SCHOOL_READ = "exam.school.read";
    public static final String FILES = "exam.file.manage";
    public static final String FILE_READ = "exam.file.read";
    public static final String COMMITTEES = "exam.committee.manage";
    public static final String CALENDAR = "exam.calendar.manage";
    public static final String CALENDAR_READ = "exam.calendar.read";
    public static final String BOOKINGS = "exam.booking.manage";
    public static final String CONDUCT = "exam.test.conduct";
    public static final String RECORD = "exam.result.record";
    public static final String DECIDE = "exam.exception.decide";

    /**
     * Configuration (DL-031, DL-032, DL-037, DL-043; values provisional, ADR-064 decision 13).
     *
     * @param classTests licence class -> test types to pass, in order
     */
    public record Rules(int expiryMonths, int failedAttemptsThreshold, int extraTrainingHours,
                        Map<String, List<String>> classTests, ZoneId zone, LocalTime workingFrom,
                        LocalTime workingTo, Duration minimumRoute) {
        public Rules {
            classTests = Map.copyOf(classTests);
            if (expiryMonths < 1 || expiryMonths > 60 || failedAttemptsThreshold < 1 || failedAttemptsThreshold > 10
                    || extraTrainingHours < 0 || extraTrainingHours > 100 || !workingFrom.isBefore(workingTo)
                    || minimumRoute.isNegative()) {
                throw new IllegalArgumentException("itms.examination settings out of range");
            }
        }
    }

    private final Ports.Repository repo;
    private final Ports.Licensing licensing;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;
    private final Rules rules;
    private final SecureRandom seeds = new SecureRandom();

    public ExaminationService(Ports.Repository repo, Ports.Licensing licensing, AuditRecorder audit,
                              DomainEventRecorder events, Clock clock, Rules rules) {
        this.repo = repo;
        this.licensing = licensing;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.rules = rules;
    }

    // ------------------------------------------------------------------ settings (DL-043, DL-044)

    @Transactional
    public Exams.TestType saveTestType(Exams.TestType type, Ports.Access caller) {
        Exams.TestType before = repo.testType(type.code()).orElse(null);
        repo.saveTestType(type, caller.userId(), clock.instant());
        audit.record("EXAM_TEST_TYPE_SAVED", "TestType", type.code(), before == null ? null : summary(before),
                summary(type), null);
        return type;
    }

    @Transactional
    public Exams.CertificateType saveCertificateType(Exams.CertificateType type, Ports.Access caller) {
        repo.saveCertificateType(type);
        audit.record("EXAM_CERTIFICATE_TYPE_SAVED", "CertificateType", type.code(), null,
                Map.of("hours", type.hours()), null);
        return type;
    }

    @Transactional
    public Exams.Question addQuestion(String testTypeCode, String language, String category, int weight, String text,
                                      List<String> options, int correct, Ports.Access caller) {
        Exams.TestType type = testType(testTypeCode);
        if (type.kind() != Exams.TestKind.THEORY) {
            throw DomainException.invalid("NOT_A_THEORY_TEST", "questions belong to theory tests", "DL-033");
        }
        Exams.Question q = new Exams.Question(UUID.randomUUID(), testTypeCode, language, category, weight, text,
                options, correct, true);
        repo.insertQuestion(q, caller.userId(), clock.instant());
        audit.record("EXAM_QUESTION_ADDED", "Question", q.id().toString(), null,
                Map.of("testType", testTypeCode, "language", language, "weight", weight), null);
        return q;
    }

    @Transactional
    public Exams.School registerSchool(String code, String nameNl, String nameEn, String region, Ports.Access caller) {
        requireRegion(caller, SCHOOLS, region);
        Exams.School s = new Exams.School(UUID.randomUUID(), code, nameNl, nameEn, region, "ACTIVE");
        repo.insertSchool(s, caller.userId(), clock.instant());
        audit.record("DRIVING_SCHOOL_REGISTERED", "DrivingSchool", s.id().toString(), null,
                Map.of("code", code, "region", region), null);
        return s;
    }

    @Transactional
    public Exams.Branch addBranch(UUID schoolId, String code, String address, String region, Ports.Access caller) {
        Exams.School s = schoolInScope(schoolId, caller, SCHOOLS);
        Exams.Branch b = new Exams.Branch(UUID.randomUUID(), s.id(), code, address, region);
        repo.insertBranch(b);
        audit.record("DRIVING_SCHOOL_BRANCH_ADDED", "DrivingSchool", s.id().toString(), null,
                Map.of("branch", code), null);
        return b;
    }

    @Transactional
    public Exams.SchoolVehicle addVehicle(UUID schoolId, String plate, String classCode, boolean dualControls,
                                          Ports.Access caller) {
        Exams.School s = schoolInScope(schoolId, caller, SCHOOLS);
        if (!dualControls) {
            throw DomainException.invalid("DUAL_CONTROLS_REQUIRED", "a training vehicle needs dual controls",
                    "DL-044");
        }
        Exams.SchoolVehicle v = new Exams.SchoolVehicle(UUID.randomUUID(), s.id(), plate, classCode, true);
        repo.insertVehicle(v);
        audit.record("DRIVING_SCHOOL_VEHICLE_ADDED", "DrivingSchool", s.id().toString(), null,
                Map.of("classCode", classCode), null);
        return v;
    }

    @Transactional
    public Exams.Instructor addInstructor(UUID schoolId, String userId, String trafficFileNumber, String grade,
                                          Set<String> classes, Ports.Access caller) {
        Exams.School s = schoolInScope(schoolId, caller, SCHOOLS);
        if (classes.isEmpty()) {
            throw DomainException.invalid("INSTRUCTOR_CLASSES_REQUIRED", "an instructor teaches at least one class",
                    "DL-044");
        }
        Exams.Instructor i = new Exams.Instructor(UUID.randomUUID(), s.id(), userId, trafficFileNumber, grade,
                classes, "ACTIVE");
        repo.insertInstructor(i);
        audit.record("DRIVING_SCHOOL_INSTRUCTOR_ADDED", "DrivingSchool", s.id().toString(), null,
                Map.of("instructorId", i.id().toString(), "grade", grade), null);
        return i;
    }

    @Transactional
    public UUID recordComplaint(UUID schoolId, UUID instructorId, String summary, Ports.Access caller) {
        Exams.School s = schoolInScope(schoolId, caller, SCHOOLS);
        if (instructorId != null && repo.instructor(instructorId).filter(i -> i.schoolId().equals(s.id())).isEmpty()) {
            throw DomainException.missing("INSTRUCTOR_NOT_FOUND", "no such instructor at this school", "DL-045");
        }
        UUID id = UUID.randomUUID();
        repo.insertComplaint(id, s.id(), instructorId, summary, clock.instant(), caller.userId());
        audit.record("DRIVING_SCHOOL_COMPLAINT_RECORDED", "DrivingSchool", s.id().toString(), null,
                Map.of("complaintId", id.toString()), null);
        return id;
    }

    /** DL-045: pass rate on first attempts and complaints, for a school or one of its instructors. */
    @Transactional(readOnly = true)
    public Exams.Rating rating(UUID schoolId, UUID instructorId, Ports.Access caller) {
        schoolInScope(schoolId, caller, SCHOOL_READ);
        return repo.rating(schoolId, instructorId);
    }

    // ------------------------------------------------------------------ learning files (DL-030..DL-032)

    @Transactional
    public Exams.LearningFile openFile(String trafficFileNumber, String classCode, String gearbox, String region,
                                       UUID schoolId, UUID instructorId, Ports.Access caller) {
        requireRegion(caller, FILES, region);
        if (!rules.classTests().containsKey(classCode)) {
            throw DomainException.invalid("CLASS_HAS_NO_TESTS", "no tests are configured for class " + classCode,
                    "DL-043");
        }
        if (instructorId != null && repo.instructor(instructorId)
                .filter(i -> i.schoolId().equals(schoolId) && i.classes().contains(classCode)).isEmpty()) {
            throw DomainException.invalid("INSTRUCTOR_INVALID",
                    "the instructor must belong to the school and teach the class", "DL-044");
        }
        Instant now = clock.instant();
        Exams.LearningFile f = new Exams.LearningFile(UUID.randomUUID(),
                repo.nextFileNumber(LocalDate.ofInstant(now, rules.zone()).getYear()), trafficFileNumber, classCode,
                gearbox, region, schoolId, instructorId, Exams.FileStatus.OPEN, 0, 0, 0, now, now, 1);
        try {
            repo.insertFile(f, caller.userId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw DomainException.conflict("LEARNING_FILE_EXISTS",
                    "this person already has a live learning file for class " + classCode, "DL-030");
        }
        audit.record("LEARNING_FILE_OPENED", "LearningFile", f.number(), null, fileSummary(f), null);
        events.record("itms.learning-file.status-changed", f.number(), event(f));
        return f;
    }

    @Transactional
    public Exams.LearningFile move(UUID fileId, String action, String reason, Ports.Access caller) {
        Exams.LearningFile f = fileInScope(fileId, caller, FILES, true);
        Exams.FileStatus target = Exams.move(f, action);
        if (target == Exams.FileStatus.CANCELLED && (reason == null || reason.isBlank())) {
            throw DomainException.invalid("REASON_REQUIRED", "cancelling a learning file needs a reason", "DL-030");
        }
        return save(f, target, f.failedAttempts(), f.extraHoursRequired(), f.trainingHours(), true, reason,
                caller.userId());
    }

    /** Training hours count as progress (DL-031) and towards the extra hours after repeated failures (DL-032). */
    @Transactional
    public Exams.LearningFile recordTraining(UUID fileId, UUID instructorId, int hours, String certificateType,
                                             LocalDate heldOn, Ports.Access caller) {
        Exams.LearningFile f = fileInScope(fileId, caller, FILES, true);
        if (f.closed() || hours < 1 || hours > 12 || heldOn.isAfter(LocalDate.now(clock.withZone(rules.zone())))) {
            throw DomainException.invalid("TRAINING_INVALID",
                    "1-12 hours, not in the future, on a file that is still open", "DL-031", "DL-032");
        }
        if (certificateType != null && repo.certificateType(certificateType).isEmpty()) {
            throw DomainException.missing("CERTIFICATE_TYPE_NOT_FOUND", "unknown training certificate type",
                    "DL-044");
        }
        repo.insertTraining(UUID.randomUUID(), f.id(), instructorId, hours, certificateType, heldOn,
                clock.instant(), caller.userId());
        Exams.FileStatus status = f.status() == Exams.FileStatus.OPEN ? Exams.FileStatus.IN_TRAINING : f.status();
        return save(f, status, f.failedAttempts(), f.extraHoursRequired(), f.trainingHours() + hours,
                true, null, caller.userId());
    }

    @Transactional(readOnly = true)
    public Exams.LearningFile file(UUID fileId, Ports.Access caller) {
        return fileInScope(fileId, caller, FILE_READ, false);
    }

    @Transactional(readOnly = true)
    public List<Ports.Result> results(UUID fileId, Ports.Access caller) {
        fileInScope(fileId, caller, FILE_READ, false);
        return repo.results(fileId);
    }

    /** DL-031: files without progress for the configured months expire. */
    @Transactional
    public int expireStale(int batchSize) {
        Instant now = clock.instant();
        Instant before = LocalDate.ofInstant(now, rules.zone()).minusMonths(rules.expiryMonths())
                .atStartOfDay(rules.zone()).toInstant();
        CurrentActor system = CurrentActor.system("learning-file-expiry");
        int n = 0;
        for (Exams.LearningFile f : repo.stale(before.plusSeconds(86_400), batchSize)) {
            if (!Exams.expired(f, now, rules.zone(), rules.expiryMonths())) {
                continue;
            }
            Exams.LearningFile next = with(f, Exams.FileStatus.EXPIRED, f.failedAttempts(), f.extraHoursRequired(),
                    f.trainingHours(), f.lastProgressAt());
            repo.updateFile(next, "no progress for " + rules.expiryMonths() + " months", system.userId(), now, true);
            audit.recordAs(system, "LEARNING_FILE_EXPIRED", "LearningFile", f.number(),
                    Map.of("status", f.status().name()), Map.of("status", "EXPIRED"), null);
            events.recordAs(system, "itms.learning-file.status-changed", f.number(), event(next));
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ committees, calendar (DL-040..DL-042)

    @Transactional
    public Exams.Committee createCommittee(String code, String location, String region, LocalDate from, LocalDate to,
                                           List<Exams.Member> members, Ports.Access caller) {
        requireRegion(caller, COMMITTEES, region);
        Exams.Committee c = new Exams.Committee(UUID.randomUUID(), code, location, region, from, to, members);
        repo.insertCommittee(c, caller.userId(), clock.instant());
        audit.record("EXAM_COMMITTEE_CREATED", "Committee", code, null,
                Map.of("location", location, "members", members.size()), null);
        return c;
    }

    /** DL-041: only the committee's head builds its calendar. */
    @Transactional
    public Exams.Slot createSlot(UUID committeeId, String classCode, String testTypeCode, Instant startsAt,
                                 int capacity, Ports.Access caller) {
        Exams.Committee c = committee(committeeId);
        requireRegion(caller, CALENDAR, c.region());
        if (!c.has(caller.userId(), Exams.MemberRole.HEAD)) {
            throw DomainException.outOfScope("only the committee's head builds its calendar", "DL-041");
        }
        testType(testTypeCode);
        if (!startsAt.isAfter(clock.instant()) || !c.validOn(LocalDate.ofInstant(startsAt, rules.zone()))
                || capacity < 1 || capacity > 500) {
            throw DomainException.invalid("SLOT_INVALID",
                    "a future slot within the committee's validity, capacity 1-500", "DL-041");
        }
        Exams.Slot s = new Exams.Slot(UUID.randomUUID(), c.id(), c.location(), classCode, testTypeCode, startsAt,
                capacity, 0);
        try {
            repo.insertSlot(s, caller.userId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw DomainException.conflict("SLOT_EXISTS", "the committee already has this slot", "DL-041");
        }
        audit.record("EXAM_SLOT_CREATED", "Committee", c.code(), null,
                Map.of("slotId", s.id().toString(), "startsAt", startsAt.toString(), "capacity", capacity), null);
        return s;
    }

    /** DL-042: past and future calendars of a location with their usage. */
    @Transactional(readOnly = true)
    public List<Ports.SlotUsage> usage(String location, String region, Instant from, Instant to,
                                       Ports.Access caller) {
        requireRegion(caller, CALENDAR_READ, region);
        if (!from.isBefore(to) || Duration.between(from, to).toDays() > 400) {
            throw DomainException.invalid("PERIOD_INVALID", "from before to, at most 400 days", "DL-042");
        }
        return repo.usage(location, from, to);
    }

    // ------------------------------------------------------------------ bookings (DL-032, DL-035, DL-043)

    @Transactional
    public Exams.Booking book(UUID fileId, UUID slotId, Ports.Access caller) {
        Exams.LearningFile f = fileInScope(fileId, caller, BOOKINGS, true);
        Exams.Slot slot = repo.slotForUpdate(slotId).orElseThrow(() -> DomainException.missing("SLOT_NOT_FOUND",
                "no such examination slot", "DL-041"));
        Exams.TestType type = testType(slot.testTypeCode());
        if (!f.mayBook()) {
            throw DomainException.conflict("FILE_NOT_READY", f.status() == Exams.FileStatus.FAILED
                    ? "the learner needs " + (f.extraHoursRequired() - f.trainingHours()) + " more training hours"
                    : "the learning file is not ready for a test", "DL-030", "DL-032");
        }
        if (!slot.classCode().equals(f.classCode()) || !rules.classTests().get(f.classCode()).contains(type.code())) {
            throw DomainException.invalid("SLOT_CLASS_MISMATCH", "the slot is for another class or test", "DL-041");
        }
        if (slot.full() || !slot.startsAt().isAfter(clock.instant())) {
            throw DomainException.conflict("SLOT_UNAVAILABLE", "the slot is full or has started", "DL-041");
        }
        repo.lastFailure(f.id(), type.code()).ifPresent(last -> {
            LocalDate earliest = LocalDate.ofInstant(last, rules.zone()).plusDays(type.retestWaitDays());
            if (LocalDate.ofInstant(slot.startsAt(), rules.zone()).isBefore(earliest)) {
                throw DomainException.conflict("RETEST_TOO_EARLY", "a retest is possible from " + earliest,
                        "DL-043");
            }
        });
        String examiner = null;
        Long seed = null;
        if (type.kind() == Exams.TestKind.THEORY) {
            seed = seeds.nextLong();
        } else {
            Exams.Committee c = committee(slot.committeeId());
            String instructorUser = f.instructorId() == null ? null
                    : repo.instructor(f.instructorId()).map(Exams.Instructor::userId).orElse(null);
            List<String> candidates = c.examiners();
            examiner = Exams.pickExaminer(candidates, instructorUser,
                    repo.pairings(candidates, f.instructorId()),
                    repo.load(candidates, LocalDate.ofInstant(slot.startsAt(), rules.zone())));
        }
        Exams.Booking b = new Exams.Booking(UUID.randomUUID(), slot.id(), f.id(), type.code(), examiner, "BOOKED",
                clock.instant());
        try {
            repo.insertBooking(b, seed, caller.userId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw DomainException.conflict("ALREADY_BOOKED", "the learner already has an open booking for this test",
                    "DL-041");
        }
        audit.record("EXAM_BOOKED", "LearningFile", f.number(), null,
                Map.of("bookingId", b.id().toString(), "testType", type.code()), null);
        return b;
    }

    // ------------------------------------------------------------------ theory test (DL-033)

    /** The paper of a theory booking in the learner's language, without the answers. */
    @Transactional(readOnly = true)
    public List<Exams.Question> paper(UUID bookingId, String language, Ports.Access caller) {
        Exams.Booking b = openBooking(bookingId);
        fileInScope(b.fileId(), caller, CONDUCT, false);
        Exams.TestType type = testType(b.testTypeCode());
        long seed = repo.paperSeed(bookingId).orElseThrow(() -> DomainException.invalid("NOT_A_THEORY_TEST",
                "this booking is not a theory test", "DL-033"));
        return Exams.drawPaper(repo.bank(type.code(), language), type.questionCount(), seed);
    }

    @Transactional
    public Ports.Result submitTheory(UUID bookingId, String language, Map<UUID, Integer> answers,
                                     Ports.Access caller) {
        List<Exams.Question> paper = paper(bookingId, language, caller);
        Exams.Booking b = openBooking(bookingId);
        Exams.TestType type = testType(b.testTypeCode());
        if (!paper.stream().map(Exams.Question::id).toList().containsAll(answers.keySet())) {
            throw DomainException.invalid("ANSWER_NOT_ON_PAPER", "answers must be to the paper's questions",
                    "DL-033");
        }
        int score = Exams.scoreTheory(paper, answers, type.maxScore());
        return record(b, type, null, null, score, score >= type.passMark(), List.of(), answers, List.of(), null,
                clock.instant(), caller.userId());
    }

    // ------------------------------------------------------------------ practical test (DL-034..DL-038)

    /**
     * An examiner's road or yard result, recorded on a tablet and possibly synced later: {@code clientResultId}
     * makes a repeated upload return the stored result (DL-034). The examiner must be the booking's examiner and
     * not the learner's instructor (DL-035).
     */
    @Transactional
    public Ports.Result recordPractical(UUID bookingId, String clientResultId, List<String> faults,
                                        List<Exams.RoutePoint> route, Instant recordedAt, UUID videoDocumentId,
                                        Ports.Access caller) {
        var stored = repo.resultByClientId(clientResultId);
        if (stored.isPresent()) {
            if (!stored.get().bookingId().equals(bookingId)) {
                throw DomainException.conflict("CLIENT_RESULT_ID_USED", "this client result id belongs to another "
                        + "booking", "DL-034");
            }
            return stored.get();
        }
        Exams.Booking b = openBooking(bookingId);
        Exams.LearningFile f = fileInScope(b.fileId(), caller, RECORD, true);
        Exams.TestType type = testType(b.testTypeCode());
        if (type.kind() == Exams.TestKind.THEORY) {
            throw DomainException.invalid("NOT_A_PRACTICAL_TEST", "theory tests are answered, not recorded",
                    "DL-034");
        }
        if (!caller.userId().equals(b.examinerId())) {
            throw DomainException.outOfScope("only the examiner assigned to the booking records its result",
                    "DL-035");
        }
        if (f.instructorId() != null && repo.instructor(f.instructorId())
                .map(i -> caller.userId().equals(i.userId())).orElse(false)) {
            throw DomainException.outOfScope("an instructor cannot examine their own learner", "DL-035");
        }
        Instant now = clock.instant();
        if (recordedAt == null || recordedAt.isAfter(now.plusSeconds(300)) || recordedAt.isBefore(now.minus(
                Duration.ofDays(30)))) {
            throw DomainException.invalid("RECORDED_AT_INVALID", "the result time must lie within the last 30 days",
                    "DL-034");
        }
        Exams.Score score = Exams.scorePractical(type, faults);
        return record(b, type, b.examinerId(), clientResultId, score.points(), score.passed(), faults, null, route,
                videoDocumentId, recordedAt, caller.userId());
    }

    /** DL-037: a supervisor accepts or rejects a flagged result; a rejected pass does not count. */
    @Transactional
    public Ports.ExceptionItem decide(UUID exceptionId, boolean accept, String reason, Ports.Access caller) {
        Ports.ExceptionItem e = repo.exceptionForUpdate(exceptionId).orElseThrow(() -> DomainException.missing(
                "EXCEPTION_NOT_FOUND", "no such exception", "DL-037"));
        Ports.Result result = repo.results(repo.resultFile(e.resultId())).stream()
                .filter(r -> r.id().equals(e.resultId())).findFirst().orElseThrow();
        Exams.LearningFile f = fileInScope(result.fileId(), caller, DECIDE, true);
        if (!"OPEN".equals(e.status()) || reason == null || reason.isBlank()) {
            throw DomainException.conflict("EXCEPTION_DECIDED", "an open exception and a reason are needed",
                    "DL-037");
        }
        if (caller.userId().equals(result.examinerId())) {
            throw DomainException.outOfScope("the examiner cannot decide on their own result", "DL-037");
        }
        repo.decideException(e.id(), accept ? "ACCEPTED" : "REJECTED", caller.userId(), reason, clock.instant());
        audit.record("EXAM_EXCEPTION_DECIDED", "LearningFile", f.number(), Map.of("status", "OPEN"),
                Map.of("status", accept ? "ACCEPTED" : "REJECTED", "reason", e.reason()), reason);
        evaluate(repo.fileForUpdate(f.id()).orElseThrow(), caller.userId());
        return repo.exceptionForUpdate(e.id()).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Ports.ExceptionItem> openExceptions(String region, Ports.Access caller) {
        requireRegion(caller, DECIDE, region);
        return repo.openExceptions(region, 200);
    }

    // ------------------------------------------------------------------ DL-036 hand-off

    /** Sends pending transfers of passed files to licensing; a failure is kept and retried on the next run. */
    public int sendTransfers(int batchSize) {
        int sent = 0;
        for (Ports.Transfer t : repo.pendingTransfers(batchSize)) {
            Exams.LearningFile f = repo.file(t.fileId()).orElseThrow();
            Ports.Result last = repo.results(f.id()).stream().filter(Ports.Result::passed)
                    .reduce((a, b) -> b).orElseThrow();
            try {
                String id = licensing.transfer(f.number(), f.trafficFileNumber(), f.classCode(), f.gearbox(),
                        LocalDate.ofInstant(last.recordedAt(), rules.zone()), f.region());
                repo.transferSent(f.id(), id, clock.instant());
                CurrentActor system = CurrentActor.system("licensing-transfer");
                audit.recordAs(system, "LEARNING_FILE_TRANSFERRED", "LearningFile", f.number(), null,
                        Map.of("licensingTransferId", id), null);
                sent++;
            } catch (RuntimeException e) {
                repo.transferFailed(f.id(), e.getClass().getSimpleName());
            }
        }
        return sent;
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Ports.Transfer> transfer(UUID fileId, Ports.Access caller) {
        fileInScope(fileId, caller, FILE_READ, false);
        return repo.transfer(fileId);
    }

    // ------------------------------------------------------------------ internals

    private Ports.Result record(Exams.Booking b, Exams.TestType type, String examiner, String clientResultId,
                                int score, boolean passed, List<String> faults, Map<UUID, Integer> answers,
                                List<Exams.RoutePoint> route, UUID video, Instant recordedAt, String actor) {
        Exams.LearningFile f = repo.fileForUpdate(b.fileId()).orElseThrow();
        Exams.Slot slot = repo.slotForUpdate(b.slotId()).orElseThrow();
        boolean first = !repo.anyResult(f.id(), type.code());
        List<String> exceptions = new ArrayList<>();
        if (type.kind() != Exams.TestKind.THEORY) {
            exceptions.addAll(Exams.exceptions(passed, recordedAt, rules.zone(), rules.workingFrom(),
                    rules.workingTo(), committee(slot.committeeId()), examiner));
            if (type.kind() == Exams.TestKind.ROAD) {
                Exams.routeException(route, rules.minimumRoute()).ifPresent(exceptions::add);
            }
        }
        Ports.Result r = new Ports.Result(UUID.randomUUID(), b.id(), f.id(), type.code(), examiner, clientResultId,
                score, passed, List.copyOf(faults), recordedAt, first, List.copyOf(exceptions));
        Instant now = clock.instant();
        repo.insertResult(r, answers, route, video, now);
        repo.markBooking(b.id(), "SAT");
        for (String reason : exceptions) {
            repo.insertException(UUID.randomUUID(), r.id(), reason, now);
        }
        audit.record("EXAM_RESULT_RECORDED", "LearningFile", f.number(), Map.of("status", f.status().name()),
                Map.of("testType", type.code(), "passed", passed, "score", score, "exceptions", exceptions), null);
        if (!passed) {
            int failed = f.failedAttempts() + 1;
            int extra = failed % rules.failedAttemptsThreshold() == 0
                    ? f.trainingHours() + rules.extraTrainingHours() : f.extraHoursRequired();
            save(f, Exams.FileStatus.FAILED, failed, extra, f.trainingHours(), true, "failed " + type.code(), actor);
        } else {
            Exams.LearningFile progressed = with(f, f.status(), f.failedAttempts(), f.extraHoursRequired(),
                    f.trainingHours(), now);
            repo.updateFile(progressed, null, actor, now, false);
            evaluate(repo.fileForUpdate(f.id()).orElseThrow(), actor);
        }
        return r;
    }

    /** Passed when every required test has a clean pass (exceptions accepted); then queued for licensing. */
    private void evaluate(Exams.LearningFile f, String actor) {
        if (f.closed()) {
            return;
        }
        List<String> required = rules.classTests().get(f.classCode());
        List<String> clean = repo.cleanPasses(f.id());
        if (clean.containsAll(required)) {
            Exams.LearningFile passed = save(f, Exams.FileStatus.PASSED, f.failedAttempts(), f.extraHoursRequired(),
                    f.trainingHours(), true, null, actor);
            repo.insertTransfer(f.id(), clock.instant());
            events.record("itms.learning-file.passed", f.number(), event(passed));
        } else if (f.status() != Exams.FileStatus.READY_FOR_TEST && f.status() != Exams.FileStatus.FAILED) {
            save(f, Exams.FileStatus.READY_FOR_TEST, f.failedAttempts(), f.extraHoursRequired(), f.trainingHours(),
                    false, null, actor);
        }
    }

    private Exams.LearningFile save(Exams.LearningFile f, Exams.FileStatus status, int failed, int extra, int hours,
                                    boolean progress, String reason, String actor) {
        Instant now = clock.instant();
        Exams.LearningFile next = with(f, status, failed, extra, hours, progress ? now : f.lastProgressAt());
        boolean changed = status != f.status();
        repo.updateFile(next, reason, actor, now, changed);
        audit.record("LEARNING_FILE_UPDATED", "LearningFile", f.number(), fileSummary(f), fileSummary(next), reason);
        if (changed) {
            events.record("itms.learning-file.status-changed", f.number(), event(next));
        }
        return next;
    }

    private static Exams.LearningFile with(Exams.LearningFile f, Exams.FileStatus status, int failed, int extra,
                                           int hours, Instant progress) {
        return new Exams.LearningFile(f.id(), f.number(), f.trafficFileNumber(), f.classCode(), f.gearbox(),
                f.region(), f.schoolId(), f.instructorId(), status, failed, extra, hours, f.openedAt(), progress,
                f.version() + 1);
    }

    private Exams.Booking openBooking(UUID id) {
        Exams.Booking b = repo.booking(id).orElseThrow(() -> DomainException.missing("BOOKING_NOT_FOUND",
                "no such booking", "DL-041"));
        if (!"BOOKED".equals(b.status())) {
            throw DomainException.conflict("BOOKING_CLOSED", "the booking has been sat or cancelled", "DL-034");
        }
        return b;
    }

    private Exams.LearningFile fileInScope(UUID id, Ports.Access caller, String permission, boolean lock) {
        Exams.LearningFile f = (lock ? repo.fileForUpdate(id) : repo.file(id)).orElseThrow(() ->
                DomainException.missing("LEARNING_FILE_NOT_FOUND", "no such learning file", "DL-030"));
        requireRegion(caller, permission, f.region());
        return f;
    }

    private Exams.School schoolInScope(UUID id, Ports.Access caller, String permission) {
        Exams.School s = repo.school(id).orElseThrow(() -> DomainException.missing("SCHOOL_NOT_FOUND",
                "no such driving school", "DL-044"));
        requireRegion(caller, permission, s.region());
        return s;
    }

    private Exams.TestType testType(String code) {
        return repo.testType(code).filter(Exams.TestType::active).orElseThrow(() -> DomainException.missing(
                "TEST_TYPE_NOT_FOUND", "no active test type " + code, "DL-043"));
    }

    private Exams.Committee committee(UUID id) {
        return repo.committee(id).orElseThrow(() -> DomainException.missing("COMMITTEE_NOT_FOUND",
                "no such committee", "DL-040"));
    }

    private static void requireRegion(Ports.Access caller, String permission, String region) {
        if (!caller.allows(permission, region)) {
            throw DomainException.outOfScope("you may not do this in region " + region, "CC-001");
        }
    }

    private static Map<String, Object> summary(Exams.TestType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", t.kind().name());
        m.put("maxScore", t.maxScore());
        m.put("passMark", t.passMark());
        m.put("retestWaitDays", t.retestWaitDays());
        m.put("feeServiceCode", t.feeServiceCode());
        m.put("questionCount", t.questionCount());
        m.put("sheetItems", t.sheet().size());
        m.put("active", t.active());
        return m;
    }

    private static Map<String, Object> fileSummary(Exams.LearningFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", f.status().name());
        m.put("classCode", f.classCode());
        m.put("failedAttempts", f.failedAttempts());
        m.put("extraHoursRequired", f.extraHoursRequired());
        m.put("trainingHours", f.trainingHours());
        return m;
    }

    private static Map<String, Object> event(Exams.LearningFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("learningFileNumber", f.number());
        m.put("trafficFileNumber", f.trafficFileNumber());
        m.put("classCode", f.classCode());
        m.put("status", f.status().name());
        m.put("region", f.region());
        return m;
    }
}

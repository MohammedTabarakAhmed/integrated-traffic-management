package gov.itms.examination.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Examination values and pure rules (DL-030..DL-045; ADR-065): learning files and their statuses, test types,
 * committees, the theory paper drawn from a weighted question bank, road-test scoring, examiner rotation and
 * exceptions, driving-school ratings. No I/O here.
 */
public final class Exams {

    private Exams() {
    }

    // ------------------------------------------------------------------ learning files (DL-030..DL-032)

    /** DL-030: the BRD's learning file statuses. */
    public enum FileStatus { OPEN, IN_TRAINING, READY_FOR_TEST, PASSED, FAILED, EXPIRED, CANCELLED }

    public enum TestKind { THEORY, YARD, ROAD }

    public record LearningFile(UUID id, String number, String trafficFileNumber, String classCode, String gearbox,
                               String region, UUID schoolId, UUID instructorId, FileStatus status,
                               int failedAttempts, int extraHoursRequired, int trainingHours, Instant openedAt,
                               Instant lastProgressAt, long version) {

        public boolean closed() {
            return status == FileStatus.PASSED || status == FileStatus.EXPIRED || status == FileStatus.CANCELLED;
        }

        /** DL-032: a learner who failed too often needs the extra hours before booking again. */
        public boolean mayBook() {
            return status == FileStatus.READY_FOR_TEST
                    || (status == FileStatus.FAILED && trainingHours >= extraHoursRequired);
        }
    }

    /** Allowed manual moves; PASSED, FAILED and EXPIRED are set by results and the expiry job only. */
    public static FileStatus move(LearningFile file, String action) {
        FileStatus target = switch (action) {
            case "start-training" -> FileStatus.IN_TRAINING;
            case "ready-for-test" -> FileStatus.READY_FOR_TEST;
            case "cancel" -> FileStatus.CANCELLED;
            default -> throw DomainException.invalid("FILE_ACTION_INVALID", "unknown learning file action", "DL-030");
        };
        boolean ok = switch (target) {
            case IN_TRAINING -> file.status() == FileStatus.OPEN || file.status() == FileStatus.FAILED;
            case READY_FOR_TEST -> file.status() == FileStatus.IN_TRAINING
                    || (file.status() == FileStatus.FAILED && file.trainingHours() >= file.extraHoursRequired());
            case CANCELLED -> !file.closed();
            default -> false;
        };
        if (!ok) {
            throw DomainException.conflict("FILE_TRANSITION_INVALID",
                    "a " + file.status() + " learning file cannot " + action, "DL-030", "DL-032");
        }
        return target;
    }

    /** DL-031: a file without progress for the configured period expires. */
    public static boolean expired(LearningFile file, Instant now, ZoneId zone, int months) {
        if (file.closed()) {
            return false;
        }
        LocalDate last = LocalDate.ofInstant(file.lastProgressAt(), zone);
        return !last.plusMonths(months).isAfter(LocalDate.ofInstant(now, zone));
    }

    // ------------------------------------------------------------------ settings (DL-043)

    /** DL-043: a test type with its scoring sheet, pass mark, retest wait and fee. */
    public record TestType(String code, TestKind kind, String nameNl, String nameEn, int maxScore, int passMark,
                           int retestWaitDays, String feeServiceCode, int questionCount, List<SheetItem> sheet,
                           boolean active) {
        public TestType {
            sheet = sheet == null ? List.of() : List.copyOf(sheet);
            if (maxScore < 1 || passMark < 1 || passMark > maxScore || retestWaitDays < 0 || questionCount < 0
                    || (kind == TestKind.THEORY && questionCount < 1)
                    || (kind != TestKind.THEORY && sheet.isEmpty())) {
                throw DomainException.invalid("TEST_TYPE_INVALID",
                        "pass mark 1..max score, a theory test needs questions, a practical test a scoring sheet",
                        "DL-043");
            }
        }
    }

    /** A line of a practical scoring sheet: penalty points when the fault is observed; critical faults fail. */
    public record SheetItem(String code, String textNl, String textEn, int penalty, boolean critical) {
    }

    // ------------------------------------------------------------------ committees and calendar (DL-040..DL-042)

    public enum MemberRole { HEAD, EXAMINER, OBSERVER }

    public record Member(String userId, MemberRole role) {
    }

    public record Committee(UUID id, String code, String location, String region, LocalDate validFrom,
                            LocalDate validTo, List<Member> members) {
        public Committee {
            members = List.copyOf(members);
            if (validTo.isBefore(validFrom) || members.stream().noneMatch(m -> m.role() == MemberRole.HEAD)
                    || members.stream().map(Member::userId).distinct().count() != members.size()) {
                throw DomainException.invalid("COMMITTEE_INVALID",
                        "a committee needs one head, distinct members and a validity that ends after it starts",
                        "DL-040");
            }
        }

        public boolean validOn(LocalDate day) {
            return !day.isBefore(validFrom) && !day.isAfter(validTo);
        }

        public boolean has(String userId, MemberRole role) {
            return members.stream().anyMatch(m -> m.userId().equals(userId) && m.role() == role);
        }

        public List<String> examiners() {
            return members.stream().filter(m -> m.role() == MemberRole.EXAMINER || m.role() == MemberRole.HEAD)
                    .map(Member::userId).toList();
        }
    }

    public record Slot(UUID id, UUID committeeId, String location, String classCode, String testTypeCode,
                       Instant startsAt, int capacity, int booked) {
        public boolean full() {
            return booked >= capacity;
        }
    }

    public record Booking(UUID id, UUID slotId, UUID fileId, String testTypeCode, String examinerId, String status,
                          Instant bookedAt) {
    }

    // ------------------------------------------------------------------ theory test (DL-033)

    public record Question(UUID id, String testTypeCode, String language, String category, int weight, String text,
                           List<String> options, int correctOption, boolean active) {
        public Question {
            options = List.copyOf(options);
            if (options.size() < 2 || options.size() > 6 || correctOption < 0 || correctOption >= options.size()
                    || weight < 1 || weight > 100) {
                throw DomainException.invalid("QUESTION_INVALID", "2..6 options, a correct one, weight 1..100",
                        "DL-033");
            }
        }
    }

    /**
     * DL-033: draws {@code count} distinct questions in one language, each draw weighted by the question's weight
     * (weighted sampling without replacement), in a random order. The seed makes a paper reproducible for disputes.
     */
    public static List<Question> drawPaper(List<Question> bank, int count, long seed) {
        List<Question> pool = new ArrayList<>(bank.stream().filter(Question::active).toList());
        if (pool.size() < count) {
            throw DomainException.conflict("QUESTION_BANK_TOO_SMALL",
                    "the question bank has " + pool.size() + " questions in this language, " + count + " needed",
                    "DL-033");
        }
        Random random = new Random(seed);
        List<Question> paper = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int total = pool.stream().mapToInt(Question::weight).sum();
            int pick = random.nextInt(total);
            int j = 0;
            while (pick >= pool.get(j).weight()) {
                pick -= pool.get(j).weight();
                j++;
            }
            paper.add(pool.remove(j));
        }
        return paper;
    }

    /** Points of a theory paper: one per correct answer, scaled to the test type's maximum score. */
    public static int scoreTheory(List<Question> paper, Map<UUID, Integer> answers, int maxScore) {
        long correct = paper.stream().filter(q -> Integer.valueOf(q.correctOption()).equals(answers.get(q.id())))
                .count();
        return (int) Math.round(correct * (double) maxScore / paper.size());
    }

    // ------------------------------------------------------------------ road test (DL-034, DL-037)

    public record RoutePoint(double lat, double lon, Instant at) {
        public RoutePoint {
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180 || at == null) {
                throw DomainException.invalid("ROUTE_INVALID", "route points need a valid position and time",
                        "DL-034");
            }
        }
    }

    /** Practical score: the maximum less the observed faults' penalties; any critical fault fails. */
    public static Score scorePractical(TestType type, List<String> faults) {
        Map<String, SheetItem> sheet = new LinkedHashMap<>();
        type.sheet().forEach(i -> sheet.put(i.code(), i));
        int score = type.maxScore();
        boolean critical = false;
        for (String f : faults) {
            SheetItem item = sheet.get(f);
            if (item == null) {
                throw DomainException.invalid("FAULT_UNKNOWN", "fault " + f + " is not on the scoring sheet",
                        "DL-043");
            }
            score -= item.penalty();
            critical |= item.critical();
        }
        score = Math.max(0, score);
        return new Score(score, !critical && score >= type.passMark());
    }

    public record Score(int points, boolean passed) {
    }

    /** DL-037: why a recorded result needs a supervisor's look. */
    public static List<String> exceptions(boolean passed, Instant recordedAt, ZoneId zone, LocalTime opens,
                                          LocalTime closes, Committee committee, String examinerId) {
        List<String> found = new ArrayList<>();
        LocalTime t = LocalTime.ofInstant(recordedAt, zone);
        if (passed && (t.isBefore(opens) || t.isAfter(closes))) {
            found.add("OUTSIDE_WORKING_HOURS");
        }
        if (!committee.has(examinerId, MemberRole.EXAMINER) && !committee.has(examinerId, MemberRole.HEAD)) {
            found.add("EXAMINER_NOT_ON_COMMITTEE");
        }
        if (!committee.validOn(LocalDate.ofInstant(recordedAt, zone))) {
            found.add("COMMITTEE_NOT_VALID");
        }
        return found;
    }

    /** A route that is too short or out of time order is suspicious too (DL-034, DL-037). */
    public static Optional<String> routeException(List<RoutePoint> route, Duration minimum) {
        if (route.size() < 2) {
            return Optional.of("ROUTE_MISSING");
        }
        for (int i = 1; i < route.size(); i++) {
            if (route.get(i).at().isBefore(route.get(i - 1).at())) {
                return Optional.of("ROUTE_TIME_ORDER");
            }
        }
        Duration length = Duration.between(route.get(0).at(), route.get(route.size() - 1).at());
        return length.compareTo(minimum) < 0 ? Optional.of("ROUTE_TOO_SHORT") : Optional.empty();
    }

    // ------------------------------------------------------------------ examiner rotation (DL-035)

    /**
     * DL-035: an examiner who is not the learner's instructor, preferring the one who has examined this instructor's
     * learners least (then the least busy, then by id) so that pairings rotate.
     */
    public static String pickExaminer(List<String> candidates, String instructorUserId,
                                      Map<String, Integer> pairingsWithInstructor, Map<String, Integer> load) {
        return candidates.stream()
                .filter(c -> !c.equals(instructorUserId))
                .min(Comparator.<String>comparingInt(c -> pairingsWithInstructor.getOrDefault(c, 0))
                        .thenComparingInt(c -> load.getOrDefault(c, 0))
                        .thenComparing(Comparator.naturalOrder()))
                .orElseThrow(() -> DomainException.conflict("NO_ELIGIBLE_EXAMINER",
                        "the committee has no examiner who is not the learner's instructor", "DL-035"));
    }

    // ------------------------------------------------------------------ schools (DL-044, DL-045)

    public record School(UUID id, String code, String nameNl, String nameEn, String region, String status) {
    }

    public record Branch(UUID id, UUID schoolId, String code, String address, String region) {
    }

    public record SchoolVehicle(UUID id, UUID schoolId, String plate, String classCode, boolean dualControls) {
    }

    public record Instructor(UUID id, UUID schoolId, String userId, String trafficFileNumber, String grade,
                             Set<String> classes, String status) {
        public Instructor {
            classes = Set.copyOf(classes);
        }
    }

    public record CertificateType(String code, String nameNl, String nameEn, int hours) {
    }

    /** DL-045: pass rate in per mille of first attempts and complaints per 100 learners. */
    public record Rating(UUID subjectId, int learners, int firstAttempts, int firstPasses, int complaints,
                         int passRatePerMille, int complaintsPer100) {
        public static Rating of(UUID subject, int learners, int firstAttempts, int firstPasses, int complaints) {
            int rate = firstAttempts == 0 ? 0 : (int) Math.round(1000.0 * firstPasses / firstAttempts);
            int per100 = learners == 0 ? 0 : (int) Math.round(100.0 * complaints / learners);
            return new Rating(subject, learners, firstAttempts, firstPasses, complaints, rate, per100);
        }
    }
}

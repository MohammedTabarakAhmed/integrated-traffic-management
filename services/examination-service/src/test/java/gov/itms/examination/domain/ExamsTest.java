package gov.itms.examination.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Pure examination rules (DL-030..DL-045). */
class ExamsTest {

    private static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");
    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");

    private static Exams.LearningFile file(Exams.FileStatus status, int failed, int extra, int hours, Instant last) {
        return new Exams.LearningFile(UUID.randomUUID(), "LF-2026-00000001", "TF-1", "LIGHT", "MANUAL", "DXB", null,
                null, status, failed, extra, hours, last, last, 1);
    }

    private static Exams.Question question(int weight) {
        return new Exams.Question(UUID.randomUUID(), "THEORY_LIGHT", "en", "SIGNS", weight, "q", List.of("a", "b"), 0,
                true);
    }

    private static final Exams.TestType ROAD = new Exams.TestType("ROAD_LIGHT", Exams.TestKind.ROAD, "طريق", "Road",
            100, 80, 7, null, 0, List.of(new Exams.SheetItem("MIRROR", "م", "Mirror", 5, false),
            new Exams.SheetItem("SPEED", "س", "Speed", 25, false),
            new Exams.SheetItem("RED_LIGHT", "إ", "Red light", 0, true)), true);

    private static Exams.Committee committee() {
        return new Exams.Committee(UUID.randomUUID(), "C1", "DXB-1", "DXB", LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 12, 31), List.of(new Exams.Member("head", Exams.MemberRole.HEAD),
                new Exams.Member("ex1", Exams.MemberRole.EXAMINER), new Exams.Member("obs", Exams.MemberRole.OBSERVER)));
    }

    @Test
    @Tag("DL-030")
    @DisplayName("learning file moves follow the BRD lifecycle; results-only statuses cannot be set by hand")
    void lifecycle() {
        assertThat(Exams.move(file(Exams.FileStatus.OPEN, 0, 0, 0, NOW), "start-training"))
                .isEqualTo(Exams.FileStatus.IN_TRAINING);
        assertThat(Exams.move(file(Exams.FileStatus.IN_TRAINING, 0, 0, 0, NOW), "ready-for-test"))
                .isEqualTo(Exams.FileStatus.READY_FOR_TEST);
        assertThat(Exams.move(file(Exams.FileStatus.FAILED, 1, 0, 0, NOW), "cancel"))
                .isEqualTo(Exams.FileStatus.CANCELLED);
        assertThatThrownBy(() -> Exams.move(file(Exams.FileStatus.PASSED, 0, 0, 0, NOW), "cancel"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("FILE_TRANSITION_INVALID"));
        assertThatThrownBy(() -> Exams.move(file(Exams.FileStatus.OPEN, 0, 0, 0, NOW), "ready-for-test"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Exams.move(file(Exams.FileStatus.OPEN, 0, 0, 0, NOW), "pass"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @Tag("DL-032")
    @DisplayName("after repeated failures the extra hours must be done before booking or moving on")
    void extraHours() {
        Exams.LearningFile owing = file(Exams.FileStatus.FAILED, 3, 10, 4, NOW);
        assertThat(owing.mayBook()).isFalse();
        assertThatThrownBy(() -> Exams.move(owing, "ready-for-test")).isInstanceOf(DomainException.class);
        Exams.LearningFile done = file(Exams.FileStatus.FAILED, 3, 10, 10, NOW);
        assertThat(done.mayBook()).isTrue();
        assertThat(Exams.move(done, "ready-for-test")).isEqualTo(Exams.FileStatus.READY_FOR_TEST);
    }

    @Test
    @Tag("DL-031")
    @DisplayName("a file without progress for the configured months expires; closed files never do")
    void expiry() {
        Instant old = NOW.minus(Duration.ofDays(370));
        assertThat(Exams.expired(file(Exams.FileStatus.IN_TRAINING, 0, 0, 0, old), NOW, DUBAI, 12)).isTrue();
        assertThat(Exams.expired(file(Exams.FileStatus.IN_TRAINING, 0, 0, 0, NOW.minus(Duration.ofDays(300))), NOW,
                DUBAI, 12)).isFalse();
        assertThat(Exams.expired(file(Exams.FileStatus.PASSED, 0, 0, 0, old), NOW, DUBAI, 12)).isFalse();
    }

    @Test
    @Tag("DL-033")
    @DisplayName("a paper is distinct, reproducible from its seed, and favours heavier questions")
    void weightedPaper() {
        List<Exams.Question> bank = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bank.add(question(i == 0 ? 100 : 1));
        }
        List<Exams.Question> a = Exams.drawPaper(bank, 5, 42);
        assertThat(a).hasSize(5).doesNotHaveDuplicates();
        assertThat(Exams.drawPaper(bank, 5, 42)).isEqualTo(a);
        int heavy = 0;
        for (long seed = 0; seed < 200; seed++) {
            if (Exams.drawPaper(bank, 1, seed).getFirst() == bank.getFirst()) {
                heavy++;
            }
        }
        assertThat(heavy).as("weight 100 of 109").isGreaterThan(150);
        assertThatThrownBy(() -> Exams.drawPaper(bank, 11, 1)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo("QUESTION_BANK_TOO_SMALL"));
    }

    @Test
    @Tag("DL-033")
    @DisplayName("theory score scales correct answers to the maximum score")
    void theoryScore() {
        List<Exams.Question> paper = List.of(question(1), question(1), question(1), question(1));
        Map<UUID, Integer> answers = new HashMap<>();
        answers.put(paper.get(0).id(), 0);
        answers.put(paper.get(1).id(), 0);
        answers.put(paper.get(2).id(), 1);
        assertThat(Exams.scoreTheory(paper, answers, 40)).isEqualTo(20);
    }

    @Test
    @Tag("DL-043")
    @DisplayName("practical score: penalties from the sheet, a critical fault fails, unknown faults are refused")
    void practicalScore() {
        assertThat(Exams.scorePractical(ROAD, List.of("MIRROR", "MIRROR"))).isEqualTo(new Exams.Score(90, true));
        assertThat(Exams.scorePractical(ROAD, List.of("SPEED"))).isEqualTo(new Exams.Score(75, false));
        assertThat(Exams.scorePractical(ROAD, List.of("RED_LIGHT"))).isEqualTo(new Exams.Score(100, false));
        assertThatThrownBy(() -> Exams.scorePractical(ROAD, List.of("NOPE"))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Exams.TestType("X", Exams.TestKind.ROAD, "x", "x", 100, 120, 0, null, 0,
                List.of(), true)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo("TEST_TYPE_INVALID"));
    }

    @Test
    @Tag("DL-035")
    @DisplayName("the examiner is never the instructor and rotates to the least paired, then least busy")
    void rotation() {
        List<String> candidates = List.of("a", "b", "instructor");
        assertThat(Exams.pickExaminer(candidates, "instructor", Map.of("a", 3, "b", 1, "instructor", 0), Map.of()))
                .isEqualTo("b");
        assertThat(Exams.pickExaminer(candidates, "instructor", Map.of(), Map.of("a", 2, "b", 5))).isEqualTo("a");
        assertThatThrownBy(() -> Exams.pickExaminer(List.of("instructor"), "instructor", Map.of(), Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("NO_ELIGIBLE_EXAMINER"));
    }

    @Test
    @Tag("DL-037")
    @DisplayName("a pass after hours, by someone off the committee or outside its validity is flagged")
    void exceptions() {
        Exams.Committee c = committee();
        Instant evening = LocalDate.of(2026, 10, 11).atTime(22, 30).atZone(DUBAI).toInstant();
        Instant morning = LocalDate.of(2026, 10, 11).atTime(10, 0).atZone(DUBAI).toInstant();
        LocalTime from = LocalTime.of(7, 0);
        LocalTime to = LocalTime.of(20, 0);
        assertThat(Exams.exceptions(true, evening, DUBAI, from, to, c, "ex1")).containsExactly("OUTSIDE_WORKING_HOURS");
        assertThat(Exams.exceptions(false, evening, DUBAI, from, to, c, "ex1")).isEmpty();
        assertThat(Exams.exceptions(true, morning, DUBAI, from, to, c, "obs")).containsExactly(
                "EXAMINER_NOT_ON_COMMITTEE");
        Instant later = LocalDate.of(2027, 1, 5).atTime(10, 0).atZone(DUBAI).toInstant();
        assertThat(Exams.exceptions(true, later, DUBAI, from, to, c, "head")).containsExactly("COMMITTEE_NOT_VALID");
    }

    @Test
    @Tag("DL-034")
    @DisplayName("a road route needs points in time order covering the minimum duration")
    void route() {
        Instant t = NOW;
        List<Exams.RoutePoint> ok = List.of(new Exams.RoutePoint(25.2, 55.3, t),
                new Exams.RoutePoint(25.21, 55.31, t.plusSeconds(900)));
        assertThat(Exams.routeException(ok, Duration.ofMinutes(10))).isEmpty();
        assertThat(Exams.routeException(List.of(ok.getFirst()), Duration.ofMinutes(10))).contains("ROUTE_MISSING");
        assertThat(Exams.routeException(List.of(ok.get(1), ok.get(0)), Duration.ofMinutes(10)))
                .contains("ROUTE_TIME_ORDER");
        assertThat(Exams.routeException(ok, Duration.ofMinutes(20))).contains("ROUTE_TOO_SHORT");
        assertThatThrownBy(() -> new Exams.RoutePoint(95, 0, t)).isInstanceOf(DomainException.class);
    }

    @Test
    @Tag("DL-040")
    @DisplayName("a committee needs a head, distinct members and an ordered validity")
    void committeeRules() {
        assertThat(committee().examiners()).containsExactly("head", "ex1");
        assertThatThrownBy(() -> new Exams.Committee(UUID.randomUUID(), "C", "L", "DXB", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 1), List.of(new Exams.Member("x", Exams.MemberRole.EXAMINER))))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Exams.Committee(UUID.randomUUID(), "C", "L", "DXB", LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 2, 1), List.of(new Exams.Member("x", Exams.MemberRole.HEAD))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @Tag("DL-045")
    @DisplayName("ratings: first-attempt pass rate per mille and complaints per 100 learners")
    void rating() {
        Exams.Rating r = Exams.Rating.of(UUID.randomUUID(), 40, 30, 21, 2);
        assertThat(r.passRatePerMille()).isEqualTo(700);
        assertThat(r.complaintsPer100()).isEqualTo(5);
        assertThat(Exams.Rating.of(UUID.randomUUID(), 0, 0, 0, 0).passRatePerMille()).isZero();
    }
}

package gov.itms.examination.api;

import static gov.itms.examination.api.ContractClient.regional;
import static gov.itms.examination.api.ContractClient.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.examination.application.ExaminationService;
import gov.itms.platform.security.AuthorisationResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Examination end to end (DL-030..DL-045, DL-036): settings, schools, committees and calendar, learning files,
 * theory paper, practical results with rotation and exceptions, the hand-off to a licensing stand-in. HTTP →
 * security → service → PostgreSQL; every request and response is checked against examination-service.v1.yaml.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.examination.transfer-initial-delay=PT1H",
        "itms.examination.expiry-initial-delay=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("DL-030")
class ExaminationApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final LicensingStub LICENSING = new LicensingStub();
    private static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");

    @DynamicPropertySource
    static void licensing(DynamicPropertyRegistry registry) {
        registry.add("itms.clients.licensing.base-url", LICENSING::baseUrl);
        registry.add("itms.clients.licensing.token-uri", () -> LICENSING.baseUrl() + "/token");
        registry.add("itms.clients.licensing.client-secret", () -> "it-secret");
    }

    @AfterAll
    static void stop() {
        LICENSING.stop();
    }

    @TestConfiguration
    static class Scopes {
        @Bean
        AuthorisationResolver authorisationResolver() {
            return new ContractClient.RegionalResolver();
        }
    }

    private static final RequestPostProcessor ADMIN = user("u-admin", "EXAM_ADMIN", "exam.settings.manage",
            "exam.committee.manage", "exam.calendar.read");
    private static final RequestPostProcessor SCHOOLS = user("u-schools", "DRIVING_SCHOOL_OFFICER",
            "exam.school.manage", "exam.school.read");
    private static final RequestPostProcessor OFFICER = regional("u-officer", "LEARNING_FILE_OFFICER", List.of("DXB"),
            "exam.file.manage", "exam.file.read", "exam.booking.manage", "exam.calendar.read", "exam.test.conduct");
    private static final RequestPostProcessor ELSEWHERE = regional("u-auh", "LEARNING_FILE_OFFICER", List.of("AUH"),
            "exam.file.manage", "exam.file.read", "exam.booking.manage");
    private static final RequestPostProcessor HEAD = user("u-head", "EXAM_COMMITTEE_HEAD", "exam.calendar.manage",
            "exam.calendar.read", "exam.result.record", "exam.file.read");
    private static final RequestPostProcessor NOT_HEAD = user("u-other", "EXAM_COMMITTEE_HEAD",
            "exam.calendar.manage");
    private static final RequestPostProcessor SUPERVISOR = user("u-sup", "EXAM_SUPERVISOR", "exam.exception.decide",
            "exam.file.read", "exam.calendar.read", "exam.school.read");

    private static RequestPostProcessor examiner(String id) {
        return user(id, "EXAMINER", "exam.result.record", "exam.file.read", "exam.exception.decide");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    @Autowired
    ExaminationService service;
    private ContractClient api;

    @BeforeEach
    void client() {
        api = new ContractClient(mvc, json, "/openapi/examination-service.v1.yaml");
    }

    private static Instant at(int daysFromToday, int hourDubai) {
        return LocalDate.now(DUBAI).plusDays(daysFromToday).atTime(hourDubai, 0).atZone(DUBAI).toInstant();
    }

    private static List<Map<String, Object>> route(Instant start) {
        return List.of(Map.of("lat", 25.20, "lon", 55.27, "at", start.toString()),
                Map.of("lat", 25.21, "lon", 55.29, "at", start.plus(15, ChronoUnit.MINUTES).toString()));
    }

    /** Test types, a question bank of four questions per language, a school with an instructor, a committee. */
    private Map<String, String> setUp(String tag) throws Exception {
        api.expect(api.put("/v1/test-types/THEORY_LIGHT", Map.of("kind", "THEORY", "nameNl", "نظري", "nameEn",
                "Theory", "maxScore", 30, "passMark", 20, "retestWaitDays", 0, "questionCount", 3, "active", true),
                ADMIN), 200);
        api.expect(api.put("/v1/test-types/ROAD_LIGHT", Map.of("kind", "ROAD", "nameNl", "طريق", "nameEn", "Road",
                "maxScore", 100, "passMark", 80, "retestWaitDays", 7, "feeServiceCode", "EXAM_ROAD", "questionCount", 0,
                "sheet", List.of(Map.of("code", "MIRROR", "textNl", "مرآة", "textEn", "Mirror", "penalty", 5,
                                "critical", false),
                        Map.of("code", "RED_LIGHT", "textNl", "إشارة", "textEn", "Red light", "penalty", 0,
                                "critical", true)), "active", true), ADMIN), 200);
        if (db.sql("select count(*) from question").query(Long.class).single() == 0) {
            for (String lang : List.of("ar", "en")) {
                for (int i = 0; i < 4; i++) {
                    api.expect(api.post("/v1/test-types/THEORY_LIGHT/questions", Map.of("language", lang,
                            "category", "SIGNS", "weight", 1 + i, "text", lang + " question " + i,
                            "options", List.of("right", "wrong", "other"), "correctOption", 0), ADMIN), 201);
                }
            }
        }
        JsonNode school = api.expect(api.post("/v1/driving-schools", Map.of("code", "SCH_" + tag, "nameNl", "مدرسة",
                "nameEn", "School " + tag, "region", "DXB"), SCHOOLS), 201);
        String schoolId = school.get("id").asString();
        api.expect(api.post("/v1/driving-schools/" + schoolId + "/branches", Map.of("code", "MAIN", "address",
                "Street 1", "region", "DXB"), SCHOOLS), 201);
        api.expect(api.post("/v1/driving-schools/" + schoolId + "/vehicles", Map.of("plate", "T " + tag,
                "classCode", "LIGHT", "dualControls", true), SCHOOLS), 201);
        assertThat(api.body(api.post("/v1/driving-schools/" + schoolId + "/vehicles", Map.of("plate", "X " + tag,
                "classCode", "LIGHT", "dualControls", false), SCHOOLS)).get("code").asString())
                .isEqualTo("DUAL_CONTROLS_REQUIRED");
        JsonNode instructor = api.expect(api.post("/v1/driving-schools/" + schoolId + "/instructors",
                Map.of("userId", "u-instructor-" + tag, "trafficFileNumber", "TF-I-" + tag, "grade", "SENIOR",
                        "classes", List.of("LIGHT")), SCHOOLS), 201);
        JsonNode committee = api.expect(api.post("/v1/committees", Map.of("code", "COM_" + tag, "location",
                "DXB-" + tag, "region", "DXB", "validFrom", LocalDate.now(DUBAI).minusDays(5).toString(),
                "validTo", LocalDate.now(DUBAI).plusDays(60).toString(), "members", List.of(
                        Map.of("userId", "u-head", "role", "HEAD"), Map.of("userId", "u-exam-" + tag, "role", "EXAMINER"),
                        Map.of("userId", "u-instructor-" + tag, "role", "EXAMINER"),
                        Map.of("userId", "u-observer", "role", "OBSERVER"))), ADMIN), 201);
        Map<String, String> ids = new HashMap<>();
        ids.put("school", schoolId);
        ids.put("instructor", instructor.get("id").asString());
        ids.put("committee", committee.get("id").asString());
        return ids;
    }

    private String slot(String committee, String testType, Instant startsAt) throws Exception {
        return api.expect(api.post("/v1/committees/" + committee + "/slots", Map.of("classCode", "LIGHT",
                "testTypeCode", testType, "startsAt", startsAt.toString(), "capacity", 5), HEAD), 201)
                .get("id").asString();
    }

    private String openFile(String trafficFile, String school, String instructor) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("trafficFileNumber", trafficFile, "classCode", "LIGHT",
                "gearbox", "MANUAL", "region", "DXB"));
        if (school != null) {
            body.put("schoolId", school);
            body.put("instructorId", instructor);
        }
        return api.expect(api.post("/v1/learning-files", body, OFFICER), 201).get("id").asString();
    }

    private JsonNode passTheory(String file, String theorySlot) throws Exception {
        String booking = api.expect(api.post("/v1/learning-files/" + file + "/bookings", Map.of("slotId", theorySlot),
                OFFICER), 201).get("id").asString();
        JsonNode paper = api.expect(api.get("/v1/bookings/" + booking + "/paper?language=en", OFFICER), 200);
        assertThat(paper.get("items")).hasSize(3);
        assertThat(paper.get("items").get(0).has("correctOption")).as("the answer is not on the paper").isFalse();
        Map<String, Integer> answers = new HashMap<>();
        paper.get("items").forEach(q -> answers.put(q.get("id").asString(), 0));
        return api.expect(api.post("/v1/bookings/" + booking + "/theory-answers", Map.of("language", "en",
                "answers", answers), OFFICER), 200);
    }

    private String status(String file) throws Exception {
        return api.expect(api.get("/v1/learning-files/" + file, OFFICER), 200).get("status").asString();
    }

    @Test
    @Tag("DL-031")
    @Tag("DL-032")
    @Tag("DL-033")
    @Tag("DL-034")
    @Tag("DL-035")
    @Tag("DL-036")
    @Tag("DL-040")
    @Tag("DL-041")
    @Tag("DL-042")
    @Tag("DL-043")
    @Tag("DL-044")
    @Tag("DL-045")
    @Tag("CC-001")
    @DisplayName("a learner trains, passes theory, fails and retakes the road test with a rotated examiner, passes "
            + "and is handed to licensing; offline re-sync is idempotent; scope and rules are enforced")
    void learnerJourney() throws Exception {
        Map<String, String> ids = setUp("A");
        String theorySlot = slot(ids.get("committee"), "THEORY_LIGHT", at(1, 9));
        String roadSlot = slot(ids.get("committee"), "ROAD_LIGHT", at(2, 9));
        String laterRoadSlot = slot(ids.get("committee"), "ROAD_LIGHT", at(10, 9));
        assertThat(api.post("/v1/committees/" + ids.get("committee") + "/slots", Map.of("classCode", "LIGHT",
                "testTypeCode", "ROAD_LIGHT", "startsAt", at(3, 9).toString(), "capacity", 5), NOT_HEAD)
                .getResponse().getStatus()).as("DL-041: only the head builds the calendar").isEqualTo(403);

        String file = openFile("TF-L-A", ids.get("school"), ids.get("instructor"));
        assertThat(api.body(api.post("/v1/learning-files", Map.of("trafficFileNumber", "TF-L-A", "classCode", "LIGHT",
                "gearbox", "MANUAL", "region", "DXB"), OFFICER)).get("code").asString()).isEqualTo("LEARNING_FILE_EXISTS");
        assertThat(api.get("/v1/learning-files/" + file, ELSEWHERE).getResponse().getStatus()).as("CC-001")
                .isEqualTo(403);
        assertThat(api.body(api.post("/v1/learning-files/" + file + "/bookings", Map.of("slotId", theorySlot),
                OFFICER)).get("code").asString()).isEqualTo("FILE_NOT_READY");

        api.expect(api.post("/v1/learning-files/" + file + "/training-sessions", Map.of("instructorId",
                ids.get("instructor"), "hours", 4, "heldOn", LocalDate.now(DUBAI).toString()), OFFICER), 200);
        assertThat(status(file)).isEqualTo("IN_TRAINING");
        api.expect(api.post("/v1/learning-files/" + file + "/transitions", Map.of("action", "ready-for-test"),
                OFFICER), 200);

        JsonNode theory = passTheory(file, theorySlot);
        assertThat(theory.get("passed").asBoolean()).isTrue();
        assertThat(theory.get("score").asInt()).isEqualTo(30);
        assertThat(status(file)).as("road test still to pass").isEqualTo("READY_FOR_TEST");

        JsonNode booking = api.expect(api.post("/v1/learning-files/" + file + "/bookings", Map.of("slotId", roadSlot),
                OFFICER), 201);
        String examinerId = booking.get("examinerId").asString();
        assertThat(examinerId).as("DL-035: never the learner's instructor").isNotEqualTo("u-instructor-A")
                .isIn("u-head", "u-exam-A");
        String roadBooking = booking.get("id").asString();
        Instant morning = at(-1, 10);
        Map<String, Object> failed = Map.of("clientResultId", "tablet-A-1", "faults", List.of("RED_LIGHT"),
                "route", route(morning), "recordedAt", morning.toString());
        assertThat(api.post("/v1/bookings/" + roadBooking + "/practical-results", failed, examiner("u-someone"))
                .getResponse().getStatus()).as("only the assigned examiner").isEqualTo(403);
        JsonNode fail = api.expect(api.post("/v1/bookings/" + roadBooking + "/practical-results", failed,
                examiner(examinerId)), 200);
        assertThat(fail.get("passed").asBoolean()).isFalse();
        JsonNode afterFail = api.expect(api.get("/v1/learning-files/" + file, OFFICER), 200);
        assertThat(afterFail.get("status").asString()).isEqualTo("FAILED");
        assertThat(afterFail.get("failedAttempts").asInt()).isEqualTo(1);

        assertThat(api.body(api.post("/v1/learning-files/" + file + "/bookings", Map.of("slotId", roadSlot),
                OFFICER)).get("code").asString()).as("DL-043: retest wait").isEqualTo("RETEST_TOO_EARLY");
        JsonNode retake = api.expect(api.post("/v1/learning-files/" + file + "/bookings",
                Map.of("slotId", laterRoadSlot), OFFICER), 201);
        String second = retake.get("examinerId").asString();
        assertThat(second).as("DL-035: rotates away from the first examiner").isNotEqualTo(examinerId)
                .isNotEqualTo("u-instructor-A");
        Map<String, Object> pass = Map.of("clientResultId", "tablet-A-2", "faults", List.of("MIRROR"),
                "route", route(morning.plusSeconds(3600)), "recordedAt", morning.plusSeconds(3600).toString());
        JsonNode passed = api.expect(api.post("/v1/bookings/" + retake.get("id").asString() + "/practical-results",
                pass, examiner(second)), 200);
        assertThat(passed.get("passed").asBoolean()).isTrue();
        assertThat(passed.get("score").asInt()).isEqualTo(95);
        assertThat(passed.get("exceptions")).isEmpty();
        JsonNode resync = api.expect(api.post("/v1/bookings/" + retake.get("id").asString() + "/practical-results",
                pass, examiner(second), "resync-key-1"), 200);
        assertThat(resync.get("id").asString()).as("DL-034: an offline re-upload returns the stored result")
                .isEqualTo(passed.get("id").asString());
        assertThat(status(file)).isEqualTo("PASSED");

        // DL-036: the hand-off retries while licensing is down, then succeeds once, idempotently keyed.
        LICENSING.failing = true;
        assertThat(service.sendTransfers(10)).isZero();
        assertThat(api.expect(api.get("/v1/learning-files/" + file + "/licensing-transfer", OFFICER), 200)
                .get("status").asString()).isEqualTo("PENDING");
        LICENSING.failing = false;
        assertThat(service.sendTransfers(10)).isEqualTo(1);
        JsonNode transfer = api.expect(api.get("/v1/learning-files/" + file + "/licensing-transfer", OFFICER), 200);
        assertThat(transfer.get("status").asString()).isEqualTo("SENT");
        assertThat(transfer.get("attempts").asInt()).isEqualTo(2);
        LicensingStub.Seen sent = LICENSING.transfers.getLast();
        assertThat(sent.authorization()).isEqualTo("Bearer it-token");
        assertThat(sent.idempotencyKey()).startsWith("exam-transfer-LF-");
        assertThat(sent.body()).contains("\"trafficFileNumber\":\"TF-L-A\"", "\"classCode\":\"LIGHT\"",
                "\"gearbox\":\"MANUAL\"", "\"region\":\"DXB\"");

        JsonNode results = api.expect(api.get("/v1/learning-files/" + file + "/results", OFFICER), 200);
        assertThat(results.get("items")).hasSize(3);

        // DL-042 usage, DL-045 rating.
        JsonNode usage = api.expect(api.get("/v1/calendar-usage?location=DXB-A&region=DXB&from="
                + at(-1, 0) + "&to=" + at(30, 0), OFFICER), 200);
        assertThat(usage.get("items")).hasSize(3);
        assertThat(usage.get("items").get(0).get("sat").asInt()).isEqualTo(1);
        JsonNode rating = api.expect(api.get("/v1/driving-schools/" + ids.get("school") + "/rating", SCHOOLS), 200);
        assertThat(rating.get("learners").asInt()).isEqualTo(1);
        assertThat(rating.get("firstAttempts").asInt()).isEqualTo(2);
        assertThat(rating.get("passRatePerMille").asInt()).isEqualTo(500);
        api.expect(api.post("/v1/driving-schools/" + ids.get("school") + "/complaints", Map.of("instructorId",
                ids.get("instructor"), "summary", "late to lessons"), SCHOOLS), 201);
        assertThat(api.expect(api.get("/v1/driving-schools/" + ids.get("school") + "/rating?instructorId="
                + ids.get("instructor"), SCHOOLS), 200).get("complaints").asInt()).isEqualTo(1);

        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.learning-file.passed'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(1);
        assertThat(db.sql("select count(*) from audit_outbox where action = 'EXAM_RESULT_RECORDED'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(3);
        assertThatThrownBy(() -> db.sql("update test_result set score = 100").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    @Tag("DL-037")
    @Tag("DL-031")
    @DisplayName("a pass recorded after working hours waits for a supervisor; the examiner cannot clear it; "
            + "stale files expire")
    void exceptionsAndExpiry() throws Exception {
        Map<String, String> ids = setUp("B");
        String theorySlot = slot(ids.get("committee"), "THEORY_LIGHT", at(1, 11));
        String roadSlot = slot(ids.get("committee"), "ROAD_LIGHT", at(2, 11));
        String file = openFile("TF-L-B", null, null);
        api.expect(api.post("/v1/learning-files/" + file + "/transitions", Map.of("action", "start-training"),
                OFFICER), 200);
        api.expect(api.post("/v1/learning-files/" + file + "/transitions", Map.of("action", "ready-for-test"),
                OFFICER), 200);
        passTheory(file, theorySlot);
        JsonNode booking = api.expect(api.post("/v1/learning-files/" + file + "/bookings", Map.of("slotId", roadSlot),
                OFFICER), 201);
        String examinerId = booking.get("examinerId").asString();
        Instant night = at(-1, 23);
        JsonNode result = api.expect(api.post("/v1/bookings/" + booking.get("id").asString() + "/practical-results",
                Map.of("clientResultId", "tablet-B-1", "faults", List.of(), "route", route(night.minusSeconds(1200)),
                        "recordedAt", night.toString()), examiner(examinerId)), 200);
        assertThat(result.get("exceptions").toString()).contains("OUTSIDE_WORKING_HOURS");
        assertThat(status(file)).as("a flagged pass does not count yet").isEqualTo("READY_FOR_TEST");

        JsonNode open = api.expect(api.get("/v1/result-exceptions?region=DXB", SUPERVISOR), 200);
        String exception = null;
        for (JsonNode e : open.get("items")) {
            if (e.get("resultId").asString().equals(result.get("id").asString())) {
                exception = e.get("id").asString();
            }
        }
        assertThat(exception).isNotNull();
        assertThat(api.post("/v1/result-exceptions/" + exception + "/decisions", Map.of("accept", true, "reason",
                "my own"), examiner(examinerId)).getResponse().getStatus()).isEqualTo(403);
        JsonNode decided = api.expect(api.post("/v1/result-exceptions/" + exception + "/decisions",
                Map.of("accept", true, "reason", "late test approved by the centre"), SUPERVISOR), 200);
        assertThat(decided.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(status(file)).isEqualTo("PASSED");

        String stale = openFile("TF-L-STALE", null, null);
        db.sql("update learning_file set last_progress_at = now() - interval '13 months' where id = cast(:id as uuid)")
                .param("id", stale).update();
        assertThat(service.expireStale(50)).isGreaterThanOrEqualTo(1);
        assertThat(status(stale)).isEqualTo("EXPIRED");
    }
}

package gov.itms.violation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import gov.itms.violation.application.CrossborderViolations;
import gov.itms.violation.application.ObjectionService;
import gov.itms.violation.application.ViolationService;
import gov.itms.violation.infrastructure.PartnerStub;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end slice test of violations: HTTP → security → service → PostgreSQL (V1 guards) → outboxes, with real HTTP
 * stand-ins for payment-service (payables) and hold-eligibility-service (suspension holds) behind a stub token
 * endpoint. Requests and responses are validated against violation-service.v1.yaml. The payment consumer is off; the
 * test hands payment confirmations to the service itself. The clock can be moved forward to reach a second
 * suspension.
 *
 * <p>Black points are added when a violation is recorded (ADR-056); UAT-1 speaks of a confirmed violation, which
 * needs objections (not built). Recording stands in for confirmation until the owner answers Q-072 (a).
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.violations.consumer-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("VF-020")
class ViolationApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final PartnerStub PARTNERS = new PartnerStub();
    private static final AtomicInteger CODES = new AtomicInteger();
    private static final AtomicReference<Duration> SHIFT = new AtomicReference<>(Duration.ZERO);
    private static OpenApiInteractionValidator contract;

    /** The system clock moved forward by {@link #SHIFT}. */
    @TestConfiguration
    static class ShiftedClock {
        @Bean
        Clock clock() {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return Instant.now().plus(SHIFT.get());
                }
            };
        }
    }

    @DynamicPropertySource
    static void partners(DynamicPropertyRegistry registry) {
        for (String client : List.of("payments", "holds", "vehicles")) {
            registry.add("itms.clients." + client + ".base-url", PARTNERS::baseUrl);
            registry.add("itms.clients." + client + ".token-uri", () -> PARTNERS.baseUrl() + "/token");
            registry.add("itms.clients." + client + ".client-id", () -> PartnerStub.CLIENT_ID);
            registry.add("itms.clients." + client + ".client-secret", () -> PartnerStub.CLIENT_SECRET);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    @Autowired
    ViolationService violations;
    @Autowired
    Clock clock;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/violation-service.v1.yaml").build();
    }

    @AfterEach
    void resetClock() {
        SHIFT.set(Duration.ZERO);
    }

    @AfterAll
    static void stopPartners() {
        PARTNERS.close();
    }

    private static RequestPostProcessor catalogueAdmin() {
        return user("u-admin", "VIOLATION_CATALOGUE_ADMIN", "violation.catalogue.manage");
    }

    private static RequestPostProcessor officer() {
        return user("u-officer", "VIOLATION_OFFICER", "violation.record", "violation.read");
    }

    private static RequestPostProcessor supervisor() {
        return user("u-super", "VIOLATION_SUPERVISOR", "violation.cancel", "violation.read");
    }

    private static RequestPostProcessor user(String sub, String role, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub)
                .claim("roles", List.of(role))
                .claim("permissions", Arrays.asList(permissions)));
    }

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who, String key) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        builder.with(who).header("X-Channel", "FIELD")
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (payload != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(payload);
        }
        MvcResult result = mvc.perform(builder).andReturn();
        int qs = path.indexOf('?');
        String requestPath = qs > 0 ? path.substring(0, qs) : path;
        SimpleRequest.Builder req = new SimpleRequest.Builder(method, requestPath).withHeader("Authorization", "Bearer t");
        if (key != null) {
            req.withHeader("Idempotency-Key", key);
        }
        if (qs > 0) {
            for (String pair : path.substring(qs + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                req.withQueryParam(kv[0], kv[1]);
            }
        }
        if (payload != null) {
            req.withContentType("application/json").withBody(payload);
        }
        SimpleResponse response = SimpleResponse.Builder.status(result.getResponse().getStatus())
                .withContentType(result.getResponse().getContentType())
                .withBody(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).build();
        ValidationReport report = result.getResponse().getStatus() < 400
                ? contract.validate(req.build(), response)
                : contract.validateResponse(requestPath, method, response);
        assertThat(report.getMessages()).as("OpenAPI contract for %s %s -> %d", method, requestPath,
                result.getResponse().getStatus()).isEmpty();
        return result;
    }

    private MvcResult postAs(String path, Object body, RequestPostProcessor who, String key) throws Exception {
        return call(post(path), Request.Method.POST, path, body, who, key);
    }

    private MvcResult postAs(String path, Object body, RequestPostProcessor who) throws Exception {
        boolean idempotent = path.equals("/v1/violations") || path.endsWith("/cancellations")
                || path.equals("/v1/violation-catalogue");
        return postAs(path, body, who, idempotent ? "it-" + UUID.randomUUID() : null);
    }

    private MvcResult getAs(String path, RequestPostProcessor who) throws Exception {
        return call(get(path), Request.Method.GET, path, null, who, null);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String code(MvcResult r) throws Exception {
        return body(r).get("code").asString();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String suffix() {
        return CODES.incrementAndGet() + "_" + Long.toString(System.nanoTime() % 1_000_000, 36).toUpperCase();
    }

    private String catalogue(long fine, int points) throws Exception {
        String code = "VC" + suffix();
        Map<String, Object> entry = new HashMap<>(Map.of("code", code, "legalArticle", "Art. 10",
                "descriptionNl", "مخالفة", "descriptionEn", "Violation " + code, "fineMinor", fine,
                "blackPoints", points, "impoundDays", 0, "suspensionDays", 0, "courtReferral", false,
                "appliesTo", "DRIVER"));
        entry.put("financialItem", "FINE_REVENUE");
        entry.put("beneficiary", "AUTHORITY");
        entry.put("effectiveFrom", today().toString());
        entry.put("reason", "new catalogue entry");
        MvcResult r = postAs("/v1/violation-catalogue", entry, catalogueAdmin());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return code;
    }

    private Map<String, Object> report(String code, String driverFile) {
        Map<String, Object> m = new HashMap<>(Map.of("catalogueCode", code,
                "occurredAt", clock.instant().minus(Duration.ofHours(1)).toString(), "region", "NORTH",
                "location", "Main road km 12", "plate", "A 1234", "ownerFileNumber", "TF-OWNER",
                "source", "OFFICER"));
        m.put("driverFileNumber", driverFile);
        return m;
    }

    private JsonNode recorded(String code, String driverFile) throws Exception {
        MvcResult r = postAs("/v1/violations", report(code, driverFile), officer());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r);
    }

    @Test
    @Tag("UAT-1")
    @Tag("VF-032")
    @Tag("VF-030")
    @DisplayName("UAT-1 (at recording, Q-072): 20 + 6 points → 26, suspended 3 months, hold placed, events; then 6 months")
    void uat1BlackPointSuspension() throws Exception {
        String file = "TF-UAT1-" + suffix().replace('_', '-');
        String twenty = catalogue(30_000, 20);
        String six = catalogue(60_000, 6);
        recorded(twenty, file);
        JsonNode before = body(getAs("/v1/black-point-accounts/" + file, officer()));
        assertThat(before.get("activePoints").asInt()).isEqualTo(20);
        assertThat(before.get("activeSuspension").isNull()).isTrue();
        int holdsBefore = PARTNERS.holds.size();

        JsonNode trigger = recorded(six, file);
        assertThat(trigger.get("blackPoints").asInt()).isEqualTo(6);
        assertThat(trigger.get("responsibleFileNumber").asString()).isEqualTo(file);
        JsonNode account = body(getAs("/v1/black-point-accounts/" + file, officer()));
        JsonNode suspension = account.get("activeSuspension");
        assertThat(suspension.get("pointsAtOrder").asInt()).as("active total at the order").isEqualTo(26);
        assertThat(suspension.get("ordinal").asInt()).isEqualTo(1);
        assertThat(suspension.get("from").asString()).isEqualTo(today().toString());
        assertThat(suspension.get("until").asString()).isEqualTo(today().plusMonths(3).minusDays(1).toString());
        assertThat(account.get("threshold").asInt()).isEqualTo(24);

        assertThat(PARTNERS.holds).hasSize(holdsBefore + 1);
        JsonNode hold = PARTNERS.holds.getLast();
        assertThat(hold.get("subject").get("id").asString()).isEqualTo(file);
        assertThat(hold.get("holdType").asString()).isEqualTo("LICENCE_SUSPENSION");
        assertThat(hold.get("startDate").asString()).isEqualTo(today().toString());
        assertThat(hold.get("endDate").asString()).isEqualTo(today().plusMonths(3).minusDays(1).toString());
        assertThat(db.sql("select event_type from event_outbox where subject = :f order by occurred_at")
                .param("f", file).query(String.class).list())
                .contains("itms.violation.points-added", "itms.violation.points-warning",
                        "itms.violation.licence-suspended");
        assertThat(db.sql("select data->>'recipientId' from event_outbox where subject = :f and event_type = "
                + "'itms.violation.licence-suspended'").param("f", file).query(String.class).single())
                .as("the holder is named for notification-service").isEqualTo(file);
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :f and action = 'LICENCE_SUSPENDED'")
                .param("f", file).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> db.sql("delete from licence_suspension where file_number = :f").param("f", file)
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("never changed");

        // The second suspension, after the first has ended, lasts 6 months.
        SHIFT.set(Duration.ofDays(100));
        recorded(twenty, file);
        recorded(six, file);
        JsonNode second = body(getAs("/v1/black-point-accounts/" + file, officer())).get("activeSuspension");
        assertThat(second.get("ordinal").asInt()).isEqualTo(2);
        assertThat(second.get("until").asString()).isEqualTo(today().plusMonths(6).minusDays(1).toString());
        assertThat(PARTNERS.holds).hasSize(holdsBefore + 2);
    }

    @Test
    @Tag("UAT-4")
    @Tag("VF-070")
    @Tag("FN-002")
    @DisplayName("UAT-4 (violation side): the fine is a payable; the payment confirmation marks the violation PAID once")
    void paymentConfirmation() throws Exception {
        String parking = catalogue(20_000, 0);
        JsonNode v = recorded(parking, null);
        String number = v.get("number").asString();
        assertThat(v.get("responsibleFileNumber").asString()).as("no driver: the owner").isEqualTo("TF-OWNER");
        assertThat(PARTNERS.payables.stream().map(p -> p.get("itemRef").asString())).contains(number);
        assertThat(PARTNERS.payableKeys).contains("payable-" + number.toLowerCase());

        assertThat(violations.onPaid(number)).isTrue();
        assertThat(violations.onPaid(number)).as("the same confirmation again").isFalse();
        assertThat(body(getAs("/v1/violations/" + number, officer())).get("status").asString()).isEqualTo("PAID");
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :n and action = 'VIOLATION_PAID'")
                .param("n", number).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from event_outbox where subject = :n and event_type = "
                + "'itms.violation.paid'").param("n", number).query(Long.class).single()).isEqualTo(1);
        assertThat(code(postAs("/v1/violations/" + number + "/cancellations", Map.of("reasonCode", "DUPLICATE"),
                supervisor()))).isEqualTo("VIOLATION_CLOSED");
    }

    @Test
    @Tag("VF-021")
    @Tag("CC-001")
    @DisplayName("cancellation needs the permission and a listed reason; it withdraws the payable and the points")
    void cancellationAndGuards() throws Exception {
        String file = "TF-C-" + suffix().replace('_', '-');
        String seatbelt = catalogue(40_000, 6);
        String number = recorded(seatbelt, file).get("number").asString();
        assertThat(postAs("/v1/violations/" + number + "/cancellations", Map.of("reasonCode", "DUPLICATE"),
                officer()).getResponse().getStatus()).isEqualTo(403);
        assertThat(code(postAs("/v1/violations/" + number + "/cancellations", Map.of("reasonCode", "BECAUSE"),
                supervisor()))).isEqualTo("CANCEL_REASON_INVALID");
        int cancelled = PARTNERS.cancelledPayables.size();
        JsonNode done = body(postAs("/v1/violations/" + number + "/cancellations",
                Map.of("reasonCode", "DUPLICATE", "note", "entered twice"), supervisor()));
        assertThat(done.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(PARTNERS.cancelledPayables).hasSize(cancelled + 1);
        assertThat(body(getAs("/v1/black-point-accounts/" + file, officer())).get("activePoints").asInt()).isZero();
        assertThat(body(getAs("/v1/violations?fileNumber=" + file, officer())).get("items")).hasSize(1);
        assertThat(postAs("/v1/violations", report(seatbelt, file), catalogueAdmin()).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(code(postAs("/v1/violations", report("NO_SUCH_CODE", file), officer())))
                .isEqualTo("CATALOGUE_NOT_IN_FORCE");
        assertThatThrownBy(() -> db.sql("update violation set fine_minor = 1 where number = :n").param("n", number)
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from violation where number = :n").param("n", number).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never deleted");
    }

    @Test
    @DisplayName("ADR-032: a retried recording with the same Idempotency-Key replays; nothing is recorded twice")
    void idempotentRecording() throws Exception {
        String parking = catalogue(20_000, 0);
        Map<String, Object> report = report(parking, null);
        String key = "it-" + UUID.randomUUID();
        MvcResult first = postAs("/v1/violations", report, officer(), key);
        MvcResult retry = postAs("/v1/violations", report, officer(), key);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(retry).get("number").asString()).isEqualTo(body(first).get("number").asString());
        assertThat(db.sql("select count(*) from violation where catalogue_code = :c").param("c", parking)
                .query(Long.class).single()).isEqualTo(1);
    }

    // ---- Increment 4: objections (VF-050..VF-052), paper books (VF-013); Increment 9: cross-border (XB-001..XB-005)

    @Autowired
    CrossborderViolations crossborder;
    @Autowired
    ObjectionService objectionService;

    private static RequestPostProcessor customer() {
        return user("c-objector", "CUSTOMER", "violation.objection.submit", "violation.read");
    }

    private static RequestPostProcessor reviewer() {
        return user("u-review", "OBJECTION_REVIEWER", "violation.objection.review", "violation.read",
                "violation.cancel");
    }

    private JsonNode expect(MvcResult r, int status) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(status);
        return text.isEmpty() ? null : json.readTree(text);
    }

    private String objectionId(String number) throws Exception {
        for (JsonNode o : expect(getAs("/v1/objections/queue?limit=500", reviewer()), 200).get("items")) {
            if (o.get("violationNumber").asString().equals(number)) {
                return o.get("id").asString();
            }
        }
        return null;
    }

    @Test
    @Tag("VF-050")
    @Tag("VF-051")
    @Tag("VF-052")
    @DisplayName("VF-050..052: an objection pauses the fine; accepted cancels it, rejected resumes payment")
    void objections() throws Exception {
        String fine = catalogue(30_000, 4);
        String file = "TF-OB-" + suffix().replace('_', '-');
        String accepted = recorded(fine, file).get("number").asString();
        String rejected = recorded(fine, file).get("number").asString();
        Map<String, Object> objection = Map.of("reasonCode", "NOT_THE_DRIVER", "explanation",
                "I was abroad that day", "evidenceIds", List.of("DOC-1"));
        assertThat(postAs("/v1/violations/" + accepted + "/objections", objection, officer(), "it-" + UUID.randomUUID())
                .getResponse().getStatus()).isEqualTo(403);
        JsonNode submitted = expect(postAs("/v1/violations/" + accepted + "/objections", objection, customer(),
                "it-" + UUID.randomUUID()), 201);
        assertThat(submitted.get("status").asString()).isEqualTo("PENDING");
        assertThat(PARTNERS.payableHolds).anyMatch(h -> h.startsWith("HOLD ") && h.endsWith("OBJECTION:" + accepted));
        assertThat(code(postAs("/v1/violations/" + accepted + "/objections", objection, customer(),
                "it-" + UUID.randomUUID()))).isEqualTo("OBJECTION_EXISTS");
        expect(postAs("/v1/violations/" + rejected + "/objections", objection, customer(), "it-" + UUID.randomUUID()),
                201);

        String acceptedId = objectionId(accepted);
        String rejectedId = objectionId(rejected);
        assertThat(acceptedId).isNotNull();
        assertThat(postAs("/v1/objections/" + acceptedId + "/decisions", Map.of("decision", "ACCEPT", "reason",
                "evidence holds"), customer(), "it-" + UUID.randomUUID()).getResponse().getStatus()).isEqualTo(403);
        int cancelled = PARTNERS.cancelledPayables.size();
        JsonNode decided = expect(postAs("/v1/objections/" + acceptedId + "/decisions", Map.of("decision", "ACCEPT",
                "reason", "evidence holds"), reviewer(), "it-" + UUID.randomUUID()), 200);
        assertThat(decided.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(body(getAs("/v1/violations/" + accepted, officer())).get("status").asString())
                .isEqualTo("CANCELLED");
        assertThat(PARTNERS.cancelledPayables).hasSize(cancelled + 1);
        assertThat(code(postAs("/v1/objections/" + acceptedId + "/decisions", Map.of("decision", "REJECT",
                "reason", "second thoughts"), reviewer(), "it-" + UUID.randomUUID()))).isEqualTo("OBJECTION_DECIDED");

        expect(postAs("/v1/objections/" + rejectedId + "/decisions", Map.of("decision", "REJECT", "reason",
                "photo shows the plate"), reviewer(), "it-" + UUID.randomUUID()), 200);
        assertThat(PARTNERS.payableHolds).anyMatch(h -> h.startsWith("LIFT ") && h.endsWith("OBJECTION:" + rejected));
        assertThat(body(getAs("/v1/violations/" + rejected, officer())).get("status").asString()).isEqualTo("OPEN");
        assertThat(code(postAs("/v1/objections/" + UUID.randomUUID() + "/decisions", Map.of("decision", "REJECT",
                "reason", "no such thing"), reviewer(), "it-" + UUID.randomUUID()))).isEqualTo("OBJECTION_NOT_FOUND");
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.violation.objection-decided' "
                + "and subject in (:a, :b)").param("a", accepted).param("b", rejected).query(Long.class).single())
                .isEqualTo(2);

        SHIFT.set(Duration.ofDays(31));
        String late = recorded(fine, file).get("number").asString();
        SHIFT.set(Duration.ofDays(62));
        assertThat(code(postAs("/v1/violations/" + late + "/objections", objection, customer(),
                "it-" + UUID.randomUUID()))).isEqualTo("OBJECTION_WINDOW_CLOSED");
    }

    @Test
    @Tag("VF-013")
    @DisplayName("VF-013: a paper book is issued to an officer, its reports entered and reconciled, or reported lost")
    void paperBooks() throws Exception {
        String fine = catalogue(10_000, 0);
        RequestPostProcessor books = user("u-officer", "VIOLATION_OFFICER", "violation.book.issue",
                "violation.book.record", "violation.book.reconcile", "violation.read", "violation.record");
        String serial = "BK-" + suffix().replace('_', '-');
        JsonNode book = expect(postAs("/v1/violation-books", Map.of("serial", serial, "region", "NORTH",
                "officerId", "u-officer"), books, "it-" + UUID.randomUUID()), 201);
        assertThat(book.get("status").asString()).isEqualTo("ISSUED");
        String number = recorded(fine, null).get("number").asString();
        expect(postAs("/v1/violation-books/" + serial + "/entries", Map.of("reportSerial", "P-0001",
                "violationNumber", number), books, "it-" + UUID.randomUUID()), 200);
        assertThat(code(postAs("/v1/violation-books/" + serial + "/reconciliation", Map.of("reportSerials",
                List.of("P-0001", "P-0002")), books, "it-" + UUID.randomUUID())))
                .isEqualTo("VIOLATION_BOOK_RECONCILIATION_INVALID");
        JsonNode reconciled = expect(postAs("/v1/violation-books/" + serial + "/reconciliation",
                Map.of("reportSerials", List.of("P-0001")), books, "it-" + UUID.randomUUID()), 200);
        assertThat(reconciled.get("status").asString()).isEqualTo("RECONCILED");
        assertThat(code(postAs("/v1/violation-books/" + serial + "/entries", Map.of("reportSerial", "P-0003",
                "violationNumber", number), books, "it-" + UUID.randomUUID()))).isEqualTo("VIOLATION_BOOK_ENTRY_INVALID");

        String lost = "BK-L" + suffix().replace('_', '-');
        expect(postAs("/v1/violation-books", Map.of("serial", lost, "region", "NORTH", "officerId", "u-officer"),
                books, "it-" + UUID.randomUUID()), 201);
        assertThat(expect(postAs("/v1/violation-books/" + lost + "/losses", Map.of("reason", "stolen from the car"),
                books, "it-" + UUID.randomUUID()), 200).get("status").asString()).isEqualTo("LOST");
        assertThat(code(postAs("/v1/violation-books/NOPE-1/losses", Map.of("reason", "x"), books,
                "it-" + UUID.randomUUID()))).isEqualTo("VIOLATION_BOOK_NOT_FOUND");
        assertThat(postAs("/v1/violation-books", Map.of("serial", "BK-X1", "region", "NORTH", "officerId", "u"),
                officer(), "it-" + UUID.randomUUID()).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @Tag("XB-001")
    @DisplayName("XB-001: a violation by a vehicle registered in a partner state is recorded and announced for sending")
    void outboundCrossborder() throws Exception {
        String fine = catalogue(25_000, 3);
        Map<String, Object> report = report(fine, null);
        report.put("registrationState", "PS1");
        JsonNode v = expect(postAs("/v1/violations", report, officer()), 201);
        String number = v.get("number").asString();
        assertThat(crossborder.link(number)).hasValueSatisfying(l -> {
            assertThat(l.partner()).isEqualTo("PS1");
            assertThat(l.direction()).isEqualTo(gov.itms.violation.application.Ports.CrossborderLinks.Direction.OUTBOUND);
        });
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.violation.crossborder-recorded' "
                + "and subject = :n").param("n", number).query(Long.class).single()).isEqualTo(1);
        report.put("registrationState", "bad state");
        assertThat(code(postAs("/v1/violations", report, officer()))).isIn("REGISTRATION_STATE_INVALID",
                "VALIDATION_FAILED");
    }

    @Test
    @Tag("XB-002")
    @Tag("XB-003")
    @Tag("XB-005")
    @DisplayName("XB-002/003/005: a partner violation is loaded without points, closed when paid there, and its "
            + "objection is decided by the partner")
    void inboundCrossborder() throws Exception {
        String fine = catalogue(40_000, 6);
        String plate = "P" + suffix().replace("_", "");
        String owner = "TF-XB-" + suffix().replace('_', '-');
        String vin = "1HGCM82633A" + String.format("%06d", System.nanoTime() % 1_000_000);
        PARTNERS.vehicles.put(plate, new String[] {vin, owner, "NORTH"});
        Instant at = clock.instant().minus(Duration.ofHours(1));

        CrossborderViolations.Loaded unknown = crossborder.loadFromPartner(new CrossborderViolations.PartnerViolation(
                "PS1", "EXT-U-" + suffix(), fine, at, "Border road", "NOPLATE", "XTS", 40_000));
        assertThat(unknown.rejectionCode()).isEqualTo("VEHICLE_NOT_REGISTERED_HERE");
        assertThat(crossborder.loadFromPartner(new CrossborderViolations.PartnerViolation("PS1", "EXT-C-" + suffix(),
                fine, at, null, plate, "EUR", 40_000)).rejectionCode()).isEqualTo("CURRENCY_NOT_SUPPORTED");

        String ext = "EXT-" + suffix();
        CrossborderViolations.PartnerViolation pv = new CrossborderViolations.PartnerViolation("PS1", ext, fine, at,
                "Border road", plate, "XTS", 35_000);
        CrossborderViolations.Loaded loaded = crossborder.loadFromPartner(pv);
        assertThat(loaded.loaded()).as("rejected: %s", loaded.rejectionCode()).isTrue();
        assertThat(crossborder.loadFromPartner(pv).violationNumber()).as("repeat").isEqualTo(loaded.violationNumber());
        String number = loaded.violationNumber();
        JsonNode v = body(getAs("/v1/violations/" + number, officer()));
        assertThat(v.get("blackPoints").asInt()).as("Q-079 (a): no points").isZero();
        assertThat(v.get("fineMinor").asLong()).isEqualTo(35_000);
        assertThat(v.get("responsibleFileNumber").asString()).isEqualTo(owner);
        assertThat(PARTNERS.payables.stream().map(p -> p.get("itemRef").asString())).contains(number);

        // XB-005: the local customer's objection goes to the issuing state, not the local queue
        expect(postAs("/v1/violations/" + number + "/objections", Map.of("reasonCode", "NOT_THE_DRIVER",
                "explanation", "sold the car"), customer(), "it-" + UUID.randomUUID()), 201);
        assertThat(objectionId(number)).as("not in the local queue").isNull();
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.violation.crossborder-objection'"
                + " and subject = :n").param("n", number).query(Long.class).single()).isEqualTo(1);
        assertThat(crossborder.objectionForwarded(number, "PS1")).isTrue();
        assertThat(crossborder.objectionForwarded(number, "PS2")).isFalse();
        assertThat(objectionService.decidedByPartner(number, "PS1", false, "photo is clear")).isTrue();
        assertThat(crossborder.partnerDecided(number, "PS1", false, "photo is clear")).isTrue();
        assertThat(PARTNERS.payableHolds).anyMatch(h -> h.startsWith("LIFT ") && h.endsWith("OBJECTION:" + number));

        // XB-003: paid in the partner state closes it here
        int cancelled = PARTNERS.cancelledPayables.size();
        assertThat(crossborder.paidInPartnerState(number, "PS2", "PAY-1")).as("wrong partner").isFalse();
        assertThat(crossborder.paidInPartnerState(number, "PS1", "PAY-1")).isTrue();
        assertThat(crossborder.paidInPartnerState(number, "PS1", "PAY-1")).as("only once").isFalse();
        assertThat(body(getAs("/v1/violations/" + number, officer())).get("status").asString()).isEqualTo("PAID");
        assertThat(PARTNERS.cancelledPayables).hasSize(cancelled + 1);

        // an accepted partner decision cancels another loaded violation
        CrossborderViolations.Loaded second = crossborder.loadFromPartner(new CrossborderViolations.PartnerViolation(
                "PS1", "EXT2-" + suffix(), fine, at, null, plate, "XTS", 1_000));
        assertThat(crossborder.partnerDecided(second.violationNumber(), "PS1", true, "accepted")).isTrue();
        assertThat(body(getAs("/v1/violations/" + second.violationNumber(), officer())).get("status").asString())
                .isEqualTo("CANCELLED");
    }

    @Test
    @Tag("XB-005")
    @DisplayName("XB-005: an objection a partner forwards against a local violation joins the queue; a second is refused")
    void partnerObjectionToLocalViolation() throws Exception {
        String fine = catalogue(12_000, 0);
        String number = recorded(fine, null).get("number").asString();
        assertThat(objectionService.submitFromPartner(number, "PS1", "driver contests")).isTrue();
        assertThat(objectionId(number)).isNotNull();
        assertThat(objectionService.submitFromPartner(number, "PS1", "again")).isFalse();
        assertThat(objectionService.submitFromPartner("VN-NOPE", "PS1", "x")).isFalse();
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.violation.objection-decided' "
                + "and subject = :n").param("n", number).query(Long.class).single()).as("refusal answered").isEqualTo(1);
    }
}

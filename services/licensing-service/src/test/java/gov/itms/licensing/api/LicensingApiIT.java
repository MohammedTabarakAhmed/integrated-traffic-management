package gov.itms.licensing.api;

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
import gov.itms.licensing.application.LicenceNumberService;
import gov.itms.licensing.application.LicenceService;
import gov.itms.licensing.application.LicenceStatusService;
import gov.itms.licensing.application.LicenceTransactions;
import gov.itms.licensing.infrastructure.PartnerStub;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
 * End-to-end slice test of licence classes: HTTP → security → service → PostgreSQL (V1 guard) → audit and event
 * outboxes, with every request and response validated against licensing-service.v1.yaml. Tests share one database, so
 * each uses its own class code.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.licensing.auto-renewal.enabled=false",
        "itms.licensing.auto-renewal.notice-days=0",
        "itms.licensing.foreign.idp-printers=AUTO_CLUB"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("DL-020")
class LicensingApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final AtomicInteger CLASSES = new AtomicInteger();
    private static OpenApiInteractionValidator contract;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    @Autowired
    LicenceNumberService numbers;
    @Autowired
    LicenceService licences;

    private static final PartnerStub PARTNERS = new PartnerStub();
    private static final AtomicInteger FILES = new AtomicInteger();

    @DynamicPropertySource
    static void partners(DynamicPropertyRegistry registry) {
        for (String client : List.of("fees", "payments", "holds", "traffic-files")) {
            registry.add("itms.clients." + client + ".base-url", PARTNERS::baseUrl);
            registry.add("itms.clients." + client + ".token-uri", () -> PARTNERS.baseUrl() + "/token");
            registry.add("itms.clients." + client + ".client-id", () -> PartnerStub.CLIENT_ID);
            registry.add("itms.clients." + client + ".client-secret", () -> PartnerStub.CLIENT_SECRET);
        }
    }

    @AfterAll
    static void stopPartners() {
        PARTNERS.close();
    }

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/licensing-service.v1.yaml").build();
    }

    private static RequestPostProcessor maker() {
        return user("u-maker", "maker", "LICENCE_CLASS_MAKER", "licensing.class.submit", "licensing.class.read");
    }

    private static RequestPostProcessor checker() {
        return user("u-checker", "checker", "LICENCE_CLASS_CHECKER", "licensing.class.approve",
                "licensing.class.read");
    }

    private static RequestPostProcessor reader() {
        return user("svc-examination", "examination", "LICENCE_CLASS_READER", "licensing.class.read");
    }

    private static RequestPostProcessor user(String sub, String username, String role, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", username)
                .claim("roles", List.of(role))
                .claim("permissions", Arrays.asList(permissions)));
    }

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who, String key) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        builder.with(who).header("X-Channel", "BACKOFFICE")
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

    private MvcResult postAs(String path, Object body, RequestPostProcessor who) throws Exception {
        return call(post(path), Request.Method.POST, path, body, who, "it-" + UUID.randomUUID());
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

    private static String newClass() {
        return "C" + CLASSES.incrementAndGet() + "_" + Long.toString(System.nanoTime() % 1_000_000, 36)
                .toUpperCase();
    }

    private static Map<String, Object> rule(String category, int minAge, int validityMonths) {
        Map<String, Object> m = new HashMap<>();
        m.put("customerCategory", category);
        m.put("minAgeYears", minAge);
        m.put("maxAgeYears", null);
        m.put("requiredTests", List.of("THEORY", "YARD", "ROAD"));
        m.put("minTrainingHours", 20);
        m.put("medicalRequired", false);
        m.put("validityMonths", validityMonths);
        m.put("residencyLinked", category == null);
        return m;
    }

    /** §6.2 light vehicle (minimum age 18); validities are test values, not BRD values (Q-074). */
    private static Map<String, Object> version(String classCode, LocalDate from, int citizenValidity) {
        Map<String, Object> m = new HashMap<>();
        m.put("classCode", classCode);
        m.put("nameNl", "مركبة خفيفة");
        m.put("nameEn", "Light vehicle");
        m.put("vehicleTypeNl", "سيارات وشاحنات خفيفة حتى 3.5 طن");
        m.put("vehicleTypeEn", "Cars and light vans up to 3.5 t");
        m.put("gearboxRecorded", true);
        m.put("rules", List.of(rule(null, 18, 24), rule("CITIZEN", 18, citizenValidity)));
        m.put("effectiveFrom", from.toString());
        m.put("reason", "new licence class");
        return m;
    }

    private String submitted(Map<String, Object> request) throws Exception {
        MvcResult r = postAs("/v1/licence-class-versions", request, maker());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("id").asString();
    }

    private String approved(Map<String, Object> request) throws Exception {
        String id = submitted(request);
        MvcResult r = postAs("/v1/licence-class-versions/" + id + "/approvals", Map.of("reason", "checked"),
                checker());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return id;
    }

    @Test
    @Tag("CC-002")
    @DisplayName("maker submits, the maker cannot approve, a checker approves once; audited; the guard freezes the row")
    void makerChecker() throws Exception {
        String classCode = newClass();
        String id = submitted(version(classCode, TODAY, 120));
        JsonNode pending = body(getAs("/v1/licence-class-versions/" + id, maker()));
        assertThat(pending.get("status").asString()).isEqualTo("PENDING");
        assertThat(pending.get("rules")).hasSize(2);

        MvcResult self = postAs("/v1/licence-class-versions/" + id + "/approvals", Map.of(),
                user("u-maker", "maker", "BOTH", "licensing.class.approve"));
        assertThat(self.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(self)).isEqualTo("SAME_MAKER_CHECKER");
        assertThat(postAs("/v1/licence-class-versions/" + id + "/approvals", Map.of(), maker()).getResponse()
                .getStatus()).as("no approve permission").isEqualTo(403);

        MvcResult ok = postAs("/v1/licence-class-versions/" + id + "/approvals", Map.of("reason", "checked"),
                checker());
        assertThat(body(ok).get("status").asString()).isEqualTo("APPROVED");
        assertThat(body(ok).get("decided").get("actorId").asString()).isEqualTo("u-checker");
        assertThat(code(postAs("/v1/licence-class-versions/" + id + "/rejections", Map.of("reason", "too late"),
                checker()))).isEqualTo("CLASS_VERSION_DECIDED");

        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :id and action in "
                        + "('LICENCE_CLASS_SUBMITTED', 'LICENCE_CLASS_APPROVED')").param("id", id).query(Long.class)
                .single()).isEqualTo(2);
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.licensing.class-approved' "
                + "and subject = :s").param("s", classCode).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> db.sql("update licence_class_version set category_rules = '[]'::jsonb "
                + "where id = cast(:id as uuid)").param("id", id).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("update licence_class_version set name_en = 'x' where id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("never changes");
        assertThatThrownBy(() -> db.sql("delete from licence_class_version where id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("never deleted");
    }

    @Test
    @Tag("DL-062")
    @DisplayName("the class in force and the rule of a customer category are read by other services")
    void inForceAndRule() throws Exception {
        String classCode = newClass();
        String id = approved(version(classCode, TODAY, 120));

        JsonNode inForce = body(getAs("/v1/licence-classes/" + classCode, reader()));
        assertThat(inForce.get("id").asString()).isEqualTo(id);
        assertThat(body(getAs("/v1/licence-classes", reader())).get("items").findValuesAsString("id")).contains(id);

        JsonNode citizen = body(getAs("/v1/licence-classes/" + classCode + "/rules/CITIZEN", reader()));
        assertThat(citizen.get("rule").get("validityMonths").asInt()).isEqualTo(120);
        assertThat(citizen.get("gearboxRecorded").asBoolean()).isTrue();
        JsonNode resident = body(getAs("/v1/licence-classes/" + classCode + "/rules/RESIDENT", reader()));
        assertThat(resident.get("rule").get("validityMonths").asInt()).isEqualTo(24);
        assertThat(resident.get("rule").get("residencyLinked").asBoolean()).isTrue();

        MvcResult before = getAs("/v1/licence-classes/" + classCode + "?on=" + TODAY.minusDays(1), reader());
        assertThat(before.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(before)).isEqualTo("CLASS_NOT_IN_FORCE");
        assertThat(getAs("/v1/licence-classes/" + classCode,
                user("u-other", "other", "NONE", "licensing.other")).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("a successor ends its predecessor; an earlier-starting version overlaps; bad input is refused")
    void successorsAndValidation() throws Exception {
        String classCode = newClass();
        String first = approved(version(classCode, TODAY.plusDays(1), 120));
        approved(version(classCode, TODAY.plusDays(30), 60));
        assertThat(body(getAs("/v1/licence-class-versions/" + first, maker())).get("effectiveTo").asString())
                .isEqualTo(TODAY.plusDays(29).toString());
        String between = submitted(version(classCode, TODAY.plusDays(40), 90));
        String overlapping = submitted(version(classCode, TODAY.plusDays(10), 90));
        assertThat(code(postAs("/v1/licence-class-versions/" + overlapping + "/approvals", Map.of(), checker())))
                .isEqualTo("CLASS_VERSION_OVERLAP");
        assertThat(body(getAs("/v1/licence-class-versions?classCode=" + classCode + "&status=PENDING", maker()))
                .get("items").findValuesAsString("id")).containsExactlyInAnyOrder(between, overlapping);

        MvcResult past = postAs("/v1/licence-class-versions", version(newClass(), TODAY.minusDays(1), 120), maker());
        assertThat(past.getResponse().getStatus()).isEqualTo(422);
        assertThat(code(past)).isEqualTo("CLASS_START_IN_PAST");

        Map<String, Object> badAges = version(newClass(), TODAY, 120);
        Map<String, Object> rule = rule(null, 21, 12);
        rule.put("maxAgeYears", 20);
        badAges.put("rules", List.of(rule));
        assertThat(code(postAs("/v1/licence-class-versions", badAges, maker()))).isEqualTo("CLASS_AGES_INVALID");

        Map<String, Object> dupes = version(newClass(), TODAY, 120);
        dupes.put("rules", List.of(rule("CITIZEN", 18, 12), rule("CITIZEN", 18, 24)));
        assertThat(code(postAs("/v1/licence-class-versions", dupes, maker()))).isEqualTo("LIST_DUPLICATES");
    }

    @Test
    @DisplayName("the maker withdraws their own pending version; a checker rejects another")
    void withdrawAndReject() throws Exception {
        String a = submitted(version(newClass(), TODAY, 120));
        String b = submitted(version(newClass(), TODAY, 120));
        assertThat(body(postAs("/v1/licence-class-versions/" + a + "/rejections", Map.of("reason", "my mistake"),
                maker())).get("status").asString()).isEqualTo("REJECTED");
        assertThat(body(postAs("/v1/licence-class-versions/" + b + "/rejections", Map.of("reason", "ages wrong"),
                checker())).get("status").asString()).isEqualTo("REJECTED");
        assertThat(db.sql("select action from audit_outbox where entity_id in (:a, :b) and action in "
                        + "('LICENCE_CLASS_WITHDRAWN', 'LICENCE_CLASS_REJECTED') order by action")
                .param("a", a).param("b", b).query(String.class).list())
                .containsExactly("LICENCE_CLASS_REJECTED", "LICENCE_CLASS_WITHDRAWN");
    }

    @Test
    @Tag("DL-061")
    @DisplayName("number range: maker-checker, overlap refused, numbers handed out in order, report and guard")
    void numberRanges() throws Exception {
        RequestPostProcessor rangeMaker = user("u-maker", "maker", "LICENCE_NUMBER_MAKER",
                "licensing.number-range.submit", "licensing.number-range.read");
        RequestPostProcessor rangeChecker = user("u-checker", "checker", "LICENCE_NUMBER_CHECKER",
                "licensing.number-range.approve", "licensing.number-range.read");
        Map<String, Object> range = new HashMap<>(Map.of("region", "IT_NORTH", "prefix", "IT", "width", 7,
                "firstNumber", 1, "lastNumber", 3, "reason", "first range"));
        MvcResult created = postAs("/v1/licence-number-ranges", range, rangeMaker);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String id = body(created).get("id").asString();
        assertThat(postAs("/v1/licence-number-ranges/" + id + "/approvals", Map.of(), rangeMaker).getResponse()
                .getStatus()).as("no approve permission").isEqualTo(403);
        JsonNode approved = body(postAs("/v1/licence-number-ranges/" + id + "/approvals", Map.of(), rangeChecker));
        assertThat(approved.get("status").asString()).isEqualTo("ACTIVE");

        range.put("region", "IT_SOUTH");
        range.put("firstNumber", 3);
        range.put("lastNumber", 9);
        assertThat(code(postAs("/v1/licence-number-ranges", range, rangeMaker))).isEqualTo("NUMBER_RANGE_OVERLAP");

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("t")
                .header("alg", "none").subject("u-officer").claim("preferred_username", "officer").build()));
        try {
            assertThat(numbers.allocate("IT_NORTH", "licence-a").licenceNumber()).isEqualTo("IT0000001");
            assertThat(numbers.allocate("IT_NORTH", "licence-b").licenceNumber()).isEqualTo("IT0000002");
        } finally {
            SecurityContextHolder.clearContext();
        }

        JsonNode report = body(getAs("/v1/licence-number-reports/IT_NORTH", rangeChecker));
        assertThat(report.get("total").asLong()).isEqualTo(3);
        assertThat(report.get("used").asLong()).isEqualTo(2);
        assertThat(report.get("available").asLong()).isEqualTo(1);
        JsonNode used = body(getAs("/v1/licence-number-ranges/" + id + "/numbers?limit=10", rangeChecker));
        assertThat(used.get("items")).hasSize(2);
        assertThat(used.get("items").get(1).get("allocatedTo").asString()).isEqualTo("licence-b");

        assertThatThrownBy(() -> db.sql("update licence_number_range set next_number = 1 where id = :id")
                .param("id", UUID.fromString(id)).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from licence_number where allocated_to = 'licence-a'").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("ADR-032: a retried submission with the same Idempotency-Key replays; without a key it is refused")
    void idempotentSubmit() throws Exception {
        Map<String, Object> request = version(newClass(), TODAY, 120);
        String key = "it-" + UUID.randomUUID();
        MvcResult first = call(post("/v1/licence-class-versions"), Request.Method.POST, "/v1/licence-class-versions",
                request, maker(), key);
        MvcResult retry = call(post("/v1/licence-class-versions"), Request.Method.POST, "/v1/licence-class-versions",
                request, maker(), key);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(retry).get("id").asString()).isEqualTo(body(first).get("id").asString());
        assertThat(db.sql("select count(*) from licence_class_version where class_code = :c")
                .param("c", request.get("classCode")).query(Long.class).single()).isEqualTo(1);

        MvcResult keyless = mvc.perform(post("/v1/licence-class-versions").with(maker())
                .header("X-Channel", "BACKOFFICE").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(version(newClass(), TODAY, 120)))).andReturn();
        assertThat(keyless.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(keyless)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    // ---- licences (slice 3.3)

    private static RequestPostProcessor sender() {
        return user("svc-examination", "examination", "EXAMINATION_TRANSFER_SENDER", "licensing.transfer.receive");
    }

    private static RequestPostProcessor officer() {
        return user("u-officer", "officer", "LICENSING_OFFICER", "licensing.licence.issue",
                "licensing.licence.condition", "licensing.licence.read");
    }

    private static RequestPostProcessor supervisor() {
        return user("u-supervisor", "supervisor", "LICENSING_SUPERVISOR", "licensing.transfer.reverse",
                "licensing.licence.read");
    }

    private static String newFile() {
        return "TF" + FILES.incrementAndGet() + Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();
    }

    /** An approved class in force today and an active number range of region {@code region}. */
    private String licensable(String region, String prefix) throws Exception {
        String classCode = newClass();
        approved(version(classCode, TODAY, 120));
        RequestPostProcessor rangeMaker = user("u-maker", "maker", "LICENCE_NUMBER_MAKER",
                "licensing.number-range.submit");
        RequestPostProcessor rangeChecker = user("u-checker", "checker", "LICENCE_NUMBER_CHECKER",
                "licensing.number-range.approve");
        if (db.sql("select count(*) from licence_number_range where region = :r").param("r", region)
                .query(Long.class).single() == 0) {
            MvcResult r = postAs("/v1/licence-number-ranges", Map.of("region", region, "prefix", prefix, "width", 6,
                    "firstNumber", 1, "lastNumber", 999, "reason", "licence numbers"), rangeMaker);
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
            postAs("/v1/licence-number-ranges/" + body(r).get("id").asString() + "/approvals", Map.of(),
                    rangeChecker);
        }
        return classCode;
    }

    private String transferred(String file, String classCode, String gearbox, String region) throws Exception {
        MvcResult r = postAs("/v1/examination-transfers", Map.of("examinationFileRef", "EX-" + file + "-" + classCode,
                "trafficFileNumber", file, "classCode", classCode, "gearbox", gearbox, "passedOn", TODAY.toString(),
                "region", region), sender());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("id").asString();
    }

    private static Map<String, Object> issue(String file, String region, String category, String residency,
                                             List<Map<String, Object>> conditions, String... transfers) {
        Map<String, Object> m = new HashMap<>();
        m.put("trafficFileNumber", file);
        m.put("region", region);
        m.put("transferIds", List.of(transfers));
        m.put("customerCategory", category);
        m.put("residencyExpiresOn", residency);
        m.put("conditions", conditions);
        PARTNERS.files.put(file, new String[] {category, residency});
        return m;
    }

    @Test
    @Tag("DL-050")
    @Tag("DL-021")
    @Tag("DL-022")
    @Tag("DL-023")
    @Tag("DL-036")
    @DisplayName("licence issue: transfer, holds checked, fee and payable, issued on payment with number, automatic-only,"
            + " probation and conditions; history, audit, event and guards")
    void licenceIssue() throws Exception {
        String classCode = licensable("IT_LIC", "LX");
        String file = newFile();
        String transfer = transferred(file, classCode, "AUTOMATIC", "IT_LIC");
        assertThat(code(postAs("/v1/examination-transfers", Map.of("examinationFileRef", "EX-" + file + "-"
                + classCode, "trafficFileNumber", file, "classCode", classCode, "gearbox", "MANUAL", "passedOn",
                TODAY.toString(), "region", "IT_LIC"), sender()))).isEqualTo("TRANSFER_EXISTS");

        MvcResult opened = postAs("/v1/licence-issues", issue(file, "IT_LIC", "CITIZEN", null,
                List.of(Map.of("code", "CORRECTIVE_LENSES")), transfer), officer());
        assertThat(opened.getResponse().getStatus()).as(opened.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode request = body(opened);
        assertThat(request.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(request.get("amountMinor").asLong()).isEqualTo(PartnerStub.FEE);
        String itemRef = "LR-" + request.get("id").asString().toUpperCase();
        assertThat(PARTNERS.payables.stream().anyMatch(p -> p.get("itemRef").asString().equals(itemRef))).isTrue();
        assertThat(PARTNERS.quotes.getLast().get("serviceCode").asString()).isEqualTo("LIC_ISSUE");
        assertThat(PARTNERS.quotes.getLast().get("quantities").get("CLASSES").asString()).isEqualTo("1");
        assertThat(PARTNERS.checks.getLast().get("subjects").get(0).get("id").asString()).isEqualTo(file);

        assertThat(code(postAs("/v1/licence-issues", issue(file, "IT_LIC", "CITIZEN", null, null, transfer),
                officer()))).isEqualTo("REQUEST_OPEN");
        assertThat(code(postAs("/v1/examination-transfers/" + transfer + "/reversal", Map.of("reason", "wrong file"),
                supervisor()))).isEqualTo("TRANSFER_IN_REQUEST");

        assertThat(licences.onPaid(itemRef)).isTrue();
        assertThat(licences.onPaid(itemRef)).as("a repeated payment event changes nothing").isFalse();

        JsonNode done = body(getAs("/v1/licence-requests/" + request.get("id").asString(), officer()));
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        String licenceId = done.get("licenceId").asString();
        JsonNode licence = body(getAs("/v1/licences/" + licenceId, officer()));
        assertThat(licence.get("licenceNumber").asString()).startsWith("LX");
        assertThat(licence.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(licence.get("expiresOn").asString()).isEqualTo(TODAY.plusMonths(120).toString());
        assertThat(licence.get("probationary").asBoolean()).isTrue();
        assertThat(licence.get("probationUntil").asString()).isEqualTo(TODAY.plusMonths(24).toString());
        assertThat(licence.get("classes").get(0).get("classCode").asString()).isEqualTo(classCode);
        assertThat(licence.get("classes").get(0).get("issuedOn").asString()).isEqualTo(TODAY.toString());
        assertThat(licence.get("classes").get(0).get("automaticOnly").asBoolean()).isTrue();
        assertThat(licence.get("conditions").get(0).get("code").asString()).isEqualTo("CORRECTIVE_LENSES");
        assertThat(body(getAs("/v1/licences?trafficFileNumber=" + file, officer())).get("items")).hasSize(1);
        assertThat(body(getAs("/v1/licences?licenceNumber=" + licence.get("licenceNumber").asString(), officer()))
                .get("items").get(0).get("id").asString()).isEqualTo(licenceId);
        assertThat(body(getAs("/v1/examination-transfers/" + transfer, officer())).get("status").asString())
                .isEqualTo("USED");
        assertThat(code(postAs("/v1/examination-transfers/" + transfer + "/reversal", Map.of("reason", "wrong file"),
                supervisor()))).isEqualTo("TRANSFER_NOT_OPEN");

        String second = transferred(file, licensable("IT_LIC", "LX"), "MANUAL", "IT_LIC");
        assertThat(code(postAs("/v1/licence-issues", issue(file, "IT_LIC", "CITIZEN", null, null, second),
                officer()))).isEqualTo("LICENCE_EXISTS");

        String conditions = "/v1/licences/" + licenceId + "/conditions";
        assertThat(body(postAs(conditions, Map.of("code", "HEARING_AID", "reason", "audiology report"), officer()))
                .get("conditions")).hasSize(2);
        assertThat(code(postAs(conditions, Map.of("code", "HEARING_AID", "reason", "again"), officer())))
                .isEqualTo("CONDITION_EXISTS");
        assertThat(code(postAs(conditions, Map.of("code", "NIGHT_ONLY", "reason", "unknown"), officer())))
                .isEqualTo("CONDITION_UNKNOWN");
        assertThat(body(postAs(conditions + "/HEARING_AID/removal", Map.of("reason", "new report"), officer()))
                .get("conditions")).hasSize(1);
        JsonNode history = body(getAs("/v1/licences/" + licenceId + "/history", officer())).get("items");
        List<String> events = new java.util.ArrayList<>();
        history.forEach(h -> events.add(h.get("event").asString()));
        assertThat(events).containsExactly("ISSUED", "CONDITION_ADDED", "CONDITION_REMOVED");
        assertThat(body(getAs("/v1/licence-condition-codes", officer())).get("items").toString())
                .contains("CORRECTIVE_LENSES");

        String number = licence.get("licenceNumber").asString();
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.licence.issued' and subject = :s")
                .param("s", number).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :s and action = 'LICENCE_ISSUED'")
                .param("s", number).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> db.sql("delete from licence where id = cast(:id as uuid)").param("id", licenceId)
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("never deleted");
        assertThatThrownBy(() -> db.sql("update licence set licence_number = 'X', version = version + 1"
                + " where id = cast(:id as uuid)").param("id", licenceId).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from licence_history where licence_id = cast(:id as uuid)")
                .param("id", licenceId).update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    @Tag("DL-050")
    @Tag("DL-062")
    @Tag("DL-036")
    @DisplayName("licence issue refusals: residency link, holds, fees down; a reversed transfer cannot be used")
    void licenceIssueRefusals() throws Exception {
        String classCode = licensable("IT_LIC", "LX");
        String file = newFile();
        String transfer = transferred(file, classCode, "MANUAL", "IT_LIC");

        assertThat(code(postAs("/v1/licence-issues", issue(file, "IT_LIC", "RESIDENT", null, null, transfer),
                officer()))).isEqualTo("RESIDENCY_EXPIRY_REQUIRED");
        assertThat(code(postAs("/v1/licence-issues", issue(file, "IT_LIC", "CITIZEN", null,
                List.of(Map.of("code", "NIGHT_ONLY")), transfer), officer()))).isEqualTo("CONDITION_UNKNOWN");

        PARTNERS.blockedFiles.add(file);
        MvcResult blocked = postAs("/v1/licence-issues", issue(file, "IT_LIC", "CITIZEN", null, null, transfer),
                officer());
        PARTNERS.blockedFiles.remove(file);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(422);
        assertThat(code(blocked)).isEqualTo("ELIGIBILITY_BLOCKED");
        assertThat(body(blocked).get("nextStep").asString()).isEqualTo("PAY_FINES");

        PARTNERS.feesDown = true;
        MvcResult down = postAs("/v1/licence-issues", issue(file, "IT_LIC", "CITIZEN", null, null, transfer),
                officer());
        PARTNERS.feesDown = false;
        assertThat(down.getResponse().getStatus()).isEqualTo(503);
        assertThat(code(down)).isEqualTo("FEES_UNAVAILABLE");

        LocalDate residency = TODAY.plusMonths(6);
        Map<String, Object> given = issue(file, "IT_LIC", "RESIDENT", residency.toString(), null, transfer);
        PARTNERS.files.put(file, new String[] {"CITIZEN", null});
        assertThat(code(postAs("/v1/licence-issues", given, officer()))).as("the traffic file decides (Q-076 a)")
                .isEqualTo("CUSTOMER_CATEGORY_MISMATCH");
        PARTNERS.files.put(file, new String[] {"RESIDENT", residency.plusDays(1).toString()});
        assertThat(code(postAs("/v1/licence-issues", given, officer()))).isEqualTo("RESIDENCY_MISMATCH");
        PARTNERS.unknownFiles.add(file);
        assertThat(code(postAs("/v1/licence-issues", given, officer()))).isEqualTo("TRAFFIC_FILE_UNKNOWN");
        PARTNERS.unknownFiles.remove(file);
        PARTNERS.trafficFilesDown = true;
        MvcResult fileDown = postAs("/v1/licence-issues", given, officer());
        PARTNERS.trafficFilesDown = false;
        assertThat(fileDown.getResponse().getStatus()).isEqualTo(503);
        assertThat(code(fileDown)).isEqualTo("TRAFFIC_FILE_UNAVAILABLE");
        PARTNERS.files.put(file, new String[] {"RESIDENT", residency.toString()});
        Map<String, Object> fromFile = new HashMap<>(given);
        fromFile.remove("customerCategory");
        fromFile.remove("residencyExpiresOn");
        MvcResult opened = postAs("/v1/licence-issues", fromFile, officer());
        assertThat(opened.getResponse().getStatus()).as(opened.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(licences.onPaid("LR-" + body(opened).get("id").asString().toUpperCase())).isTrue();
        JsonNode licence = body(getAs("/v1/licences?trafficFileNumber=" + file, officer())).get("items").get(0);
        assertThat(licence.get("expiresOn").asString()).as("never past the residency (DL-062)")
                .isEqualTo(residency.toString());
        assertThat(licence.get("classes").get(0).get("automaticOnly").asBoolean()).isFalse();

        String other = newFile();
        String reversed = transferred(other, classCode, "MANUAL", "IT_LIC");
        JsonNode r = body(postAs("/v1/examination-transfers/" + reversed + "/reversal",
                Map.of("reason", "recorded in error"), supervisor()));
        assertThat(r.get("status").asString()).isEqualTo("REVERSED");
        assertThat(code(postAs("/v1/licence-issues", issue(other, "IT_LIC", "CITIZEN", null, null, reversed),
                officer()))).isEqualTo("TRANSFER_NOT_OPEN");
        assertThat(code(postAs("/v1/licence-issues", issue(other, "IT_LIC", "CITIZEN", null, null, transfer),
                officer()))).isEqualTo("TRANSFER_NOT_OPEN");
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.licensing.transfer-reversed'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(1);
    }

    // ---- slices 3.4-3.6: renewal, replacement, change, eye tests, auto-renewal, status, NOC, foreign licences

    @Autowired
    LicenceTransactions transactions;
    @Autowired
    LicenceStatusService statuses;

    private static RequestPostProcessor counter() {
        return user("u-counter", "counter", "LICENSING_OFFICER", "licensing.licence.read", "licensing.licence.renew",
                "licensing.licence.replace", "licensing.licence.change", "licensing.auto-renewal.manage",
                "licensing.licence.cancel", "licensing.licence.status", "licensing.noc.request",
                "licensing.foreign.record", "licensing.licence.exchange", "licensing.permit.issue",
                "licensing.idp.issue", "licensing.idp.print", "licensing.exchange-country.submit");
    }

    private static RequestPostProcessor optician() {
        return user("svc-optometry", "optometry", "OPTOMETRY_CENTRE", "licensing.eye-test.record");
    }

    private JsonNode expect(MvcResult r, int status) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(status);
        return text.isEmpty() ? null : json.readTree(text);
    }

    /** Pays the licence request and returns it again. */
    private JsonNode paid(JsonNode request) throws Exception {
        String id = request.get("id").asString();
        assertThat(licences.onPaid("LR-" + id.toUpperCase())).isTrue();
        return body(getAs("/v1/licence-requests/" + id, counter()));
    }

    /** An issued CITIZEN licence of a class valid {@code months} for citizens: {file, licenceId, classCode}. */
    private String[] issued(String region, int months) throws Exception {
        String classCode = newClass();
        approved(version(classCode, TODAY, months));
        licensable(region, "LY");
        String file = newFile();
        String transfer = transferred(file, classCode, "MANUAL", region);
        JsonNode request = expect(postAs("/v1/licence-issues", issue(file, region, "CITIZEN", null, null, transfer),
                officer()), 201);
        return new String[] {file, paid(request).get("licenceId").asString(), classCode};
    }

    private void eyeTest(String file, String classCode, boolean lenses) throws Exception {
        expect(postAs("/v1/eye-tests", Map.of("trafficFileNumber", file, "centreCode", "OPT_1", "reference",
                "ET-" + newFile(), "testedOn", TODAY.toString(), "result", "PASS", "correctiveLenses", lenses,
                "permittedClasses", List.of(classCode)), optician()), 201);
    }

    @Test
    @Tag("DL-051")
    @Tag("DL-052")
    @Tag("DL-057")
    @Tag("DL-007")
    @DisplayName("DL-051/052/057: renewal in its window with an eye test, replacement of a lost card, change with "
            + "evidence; each completes on payment with a new card")
    void renewalReplacementChange() throws Exception {
        String[] early = issued("IT_TX", 120);
        assertThat(code(postAs("/v1/licences/" + early[1] + "/renewals", Map.of(), counter())))
                .isEqualTo("RENEWAL_TOO_EARLY");

        String[] l = issued("IT_TX", 2);
        String renewals = "/v1/licences/" + l[1] + "/renewals";
        assertThat(code(postAs(renewals, Map.of(), counter()))).isEqualTo("EYE_TEST_REQUIRED");
        assertThat(code(postAs("/v1/eye-tests", Map.of("trafficFileNumber", l[0], "centreCode", "OPT_1", "reference",
                "ET-F", "testedOn", TODAY.plusDays(1).toString(), "result", "PASS", "correctiveLenses", false,
                "permittedClasses", List.of(l[2])), optician()))).isEqualTo("TESTED_IN_FUTURE");
        Map<String, Object> eye = Map.of("trafficFileNumber", l[0], "centreCode", "OPT_1", "reference", "ET-" + l[0],
                "testedOn", TODAY.toString(), "result", "PASS", "correctiveLenses", true, "permittedClasses",
                List.of(l[2]));
        expect(postAs("/v1/eye-tests", eye, optician()), 201);
        assertThat(code(postAs("/v1/eye-tests", eye, optician()))).isEqualTo("EYE_TEST_EXISTS");
        assertThat(body(getAs("/v1/eye-tests?trafficFileNumber=" + l[0], counter())).get("items")).hasSize(1);

        JsonNode before = body(getAs("/v1/licences/" + l[1], counter()));
        JsonNode renewal = expect(postAs(renewals, Map.of("delivery", "COURIER", "address", "1 Main St"), counter()),
                201);
        assertThat(code(postAs(renewals, Map.of(), counter()))).isEqualTo("REQUEST_OPEN");
        assertThat(PARTNERS.quotes.getLast().get("serviceCode").asString()).isEqualTo("LIC_RENEWAL");
        paid(renewal);
        JsonNode renewed = body(getAs("/v1/licences/" + l[1], counter()));
        assertThat(LocalDate.parse(renewed.get("expiresOn").asString()))
                .isAfter(LocalDate.parse(before.get("expiresOn").asString()));
        assertThat(renewed.get("conditions").findValuesAsString("code")).contains("CORRECTIVE_LENSES");

        expect(postAs("/v1/licences/" + l[1] + "/replacements", Map.of("reason", "LOST"), counter()), 201);
        assertThat(code(postAs("/v1/licences/" + l[1] + "/replacements", Map.of("reason", "LOST"), counter())))
                .isEqualTo("REQUEST_OPEN");
        JsonNode open = body(getAs("/v1/licences/" + l[1] + "/history", counter()));
        assertThat(open.get("items").findValuesAsString("event")).contains("RENEWED", "CARD_CANCELLED");
        db.sql("select id from licence_request where licence_id = cast(:l as uuid) and kind = 'REPLACEMENT'")
                .param("l", l[1]).query(String.class).list()
                .forEach(id -> assertThat(licences.onPaid("LR-" + id.toUpperCase())).isTrue());
        JsonNode cards = body(getAs("/v1/licences/" + l[1] + "/cards", counter())).get("items");
        assertThat(cards.findValuesAsString("reason")).contains("ISSUE", "RENEWAL", "REPLACEMENT");

        Map<String, Object> change = new HashMap<>(Map.of("customerCategory", "CITIZEN", "description",
                "name corrected", "evidenceDocumentId", "!bad"));
        assertThat(code(postAs("/v1/licences/" + l[1] + "/changes", change, counter()))).isEqualTo("EVIDENCE_REQUIRED");
        change.put("evidenceDocumentId", "DOC-CHG-1");
        paid(expect(postAs("/v1/licences/" + l[1] + "/changes", change, counter()), 201));
        assertThat(body(getAs("/v1/licences/" + l[1] + "/history", counter())).get("items")
                .findValuesAsString("event")).contains("REPLACED", "CHANGED");

        String event = "evt-" + UUID.randomUUID();
        assertThat(transactions.onRegisterChange(event, l[0], "NAME")).isEqualTo(1);
        assertThat(transactions.onRegisterChange(event, l[0], "NAME")).as("each event once").isZero();
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.licence.renewed' and subject = :n")
                .param("n", renewed.get("licenceNumber").asString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Tag("DL-065")
    @DisplayName("DL-065: consent, notice, then an automatic renewal attempt; withdrawal stops it")
    void autoRenewal() throws Exception {
        String[] l = issued("IT_TX", 2);
        String consent = "/v1/licences/" + l[1] + "/auto-renewal";
        assertThat(expect(postAs(consent, Map.of("paymentMethodRef", "PM-1"), counter()), 200).get("status")
                .asString()).isEqualTo("ACTIVE");
        assertThat(body(getAs(consent, counter())).get("paymentMethodRef").asString()).isEqualTo("PM-1");
        assertThat(transactions.autoRenewalsDue(1000)).contains(UUID.fromString(l[1]));
        assertThat(transactions.autoRenewOne(UUID.fromString(l[1]))).isEqualTo("NOTICE");
        assertThat(transactions.autoRenewOne(UUID.fromString(l[1]))).as("no eye test yet").isEqualTo("FAILED");
        assertThat(transactions.autoRenewOne(UUID.fromString(l[1]))).as("one attempt per expiry").isEqualTo("NOTHING");
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.licence.auto-renewal-failed'")
                .query(Long.class).single()).isPositive();

        String[] ok = issued("IT_TX", 2);
        eyeTest(ok[0], ok[2], false);
        expect(postAs("/v1/licences/" + ok[1] + "/auto-renewal", Map.of("paymentMethodRef", "PM-2"), counter()), 200);
        assertThat(transactions.autoRenewOne(UUID.fromString(ok[1]))).isEqualTo("NOTICE");
        assertThat(transactions.autoRenewOne(UUID.fromString(ok[1]))).isEqualTo("RENEWAL");
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.licence.auto-renewal-due' "
                + "and data->>'paymentMethodRef' = 'PM-2'").query(Long.class).single()).isEqualTo(1);

        assertThat(expect(postAs(consent + "/withdrawal", null, counter()), 200).get("status").asString())
                .isEqualTo("WITHDRAWN");
        assertThat(transactions.autoRenewOne(UUID.fromString(l[1]))).isEqualTo("NOTHING");
    }

    @Test
    @Tag("DL-058")
    @Tag("DL-059")
    @Tag("VF-032")
    @DisplayName("DL-058/059: status enquiry, paid no-objection certificate, black-point suspension, surrender and "
            + "death")
    void statusSuspensionAndEnd() throws Exception {
        String[] l = issued("IT_TX", 120);
        JsonNode licence = body(getAs("/v1/licences/" + l[1], counter()));
        String number = licence.get("licenceNumber").asString();
        assertThat(body(getAs("/v1/licence-status/" + number, counter())).get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(code(getAs("/v1/licence-status/NOPE1", counter()))).isEqualTo("LICENCE_NOT_FOUND");

        paid(expect(postAs("/v1/licences/" + l[1] + "/no-objection-certificates", Map.of("purpose",
                "car rental abroad", "addressee", "Rental Co"), counter()), 201));
        JsonNode certificate = body(getAs("/v1/licences/" + l[1] + "/no-objection-certificates", counter()))
                .get("items").get(0);
        String noc = certificate.get("number").asString();
        JsonNode verified = body(getAs("/v1/no-objection-certificates/" + noc + "?code="
                + certificate.get("verificationCode").asString(), counter()));
        assertThat(verified.get("valid").asBoolean()).isTrue();
        assertThat(code(getAs("/v1/no-objection-certificates/" + noc + "?code=WRONG", counter())))
                .isEqualTo("CERTIFICATE_NOT_FOUND");

        String event = "evt-" + UUID.randomUUID();
        assertThat(statuses.onBlackPointSuspension(event, l[0], "SUSP-1", 1, TODAY, TODAY.plusMonths(3))).isEqualTo(1);
        assertThat(statuses.onBlackPointSuspension(event, l[0], "SUSP-1", 1, TODAY, TODAY.plusMonths(3))).isZero();
        assertThat(body(getAs("/v1/licence-status/" + number, counter())).get("status").asString())
                .isEqualTo("SUSPENDED");
        assertThat(body(getAs("/v1/licences/" + l[1] + "/suspensions", counter())).get("items")).hasSize(1);
        assertThat(statuses.endSuspension(UUID.fromString(l[1]))).as("not over yet").isFalse();
        assertThat(statuses.suspensionsEnded(100)).doesNotContain(UUID.fromString(l[1]));

        String cancel = "/v1/licences/" + l[1] + "/cancellations";
        assertThat(code(postAs(cancel, Map.of("ground", "COURT_ORDER", "reason", "court ruling"), counter())))
                .isEqualTo("REFERENCE_REQUIRED");
        assertThat(code(postAs(cancel, Map.of("ground", "BLACK_POINTS", "reason", "points"), counter())))
                .isEqualTo("FIELD_INVALID");
        assertThat(expect(postAs(cancel, Map.of("ground", "REQUEST", "reason", "moving abroad"), counter()), 200)
                .get("status").asString()).isEqualTo("SURRENDERED");

        String[] other = issued("IT_TX", 120);
        String death = "evt-" + UUID.randomUUID();
        assertThat(statuses.onDeceased(death, other[0])).isEqualTo(1);
        assertThat(statuses.onDeceased(death, other[0])).isZero();
        assertThat(body(getAs("/v1/licences/" + other[1], counter())).get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    @Tag("DL-054")
    @Tag("DL-055")
    @Tag("DL-056")
    @Tag("DL-063")
    @Tag("CC-002")
    @DisplayName("DL-054..056, DL-063: country rules by maker-checker, foreign licence exchange, temporary permits and "
            + "international permits")
    void foreignLicences() throws Exception {
        String classCode = newClass();
        approved(version(classCode, TODAY, 120));
        licensable("IT_TX", "LY");
        String country = "Q" + (char) ('A' + FILES.incrementAndGet() % 26);
        RequestPostProcessor ruleChecker = user("u-checker", "checker", "EXCHANGE_RULE_CHECKER",
                "licensing.exchange-country.approve", "licensing.licence.read");
        String rule = expect(postAs("/v1/exchange-country-rules", Map.of("countryCode", country, "mode",
                "DIRECT_EXCHANGE", "classMap", Map.of("B", classCode), "reason", "bilateral agreement"), counter()),
                201).get("id").asString();
        assertThat(postAs("/v1/exchange-country-rules/" + rule + "/approvals", Map.of("reason", "own rule"),
                counter()).getResponse().getStatus()).isEqualTo(403);
        assertThat(expect(postAs("/v1/exchange-country-rules/" + rule + "/approvals", Map.of("reason", "checked"),
                ruleChecker), 200).get("status").asString()).isEqualTo("APPROVED");
        String testCountry = country.charAt(0) + "Z" + country.charAt(1);
        String testRule = expect(postAs("/v1/exchange-country-rules", Map.of("countryCode", testCountry, "mode",
                "TEST_REQUIRED", "reason", "no agreement"), counter()), 201).get("id").asString();
        expect(postAs("/v1/exchange-country-rules/" + testRule + "/approvals", Map.of("reason", "checked"),
                ruleChecker), 200);
        String withdrawn = expect(postAs("/v1/exchange-country-rules", Map.of("countryCode", country, "mode",
                "TEST_REQUIRED", "reason", "second thoughts"), counter()), 201).get("id").asString();
        assertThat(expect(postAs("/v1/exchange-country-rules/" + withdrawn + "/rejections", Map.of("reason",
                "entered by mistake"), counter()), 200).get("status").asString()).isEqualTo("REJECTED");
        assertThat(body(getAs("/v1/exchange-country-rules?countryCode=" + country, counter())).get("items"))
                .hasSize(2);

        String file = newFile();
        Map<String, Object> foreign = new HashMap<>(Map.of("trafficFileNumber", file, "countryCode", country,
                "foreignNumber", "F-" + file, "classes", List.of("B"), "expiresOn", TODAY.plusYears(1).toString(),
                "language", "fr", "documentId", "DOC-F-" + file));
        String untranslated = expect(postAs("/v1/foreign-licences", foreign, counter()), 201).get("id").asString();
        assertThat(code(postAs("/v1/foreign-licences", foreign, counter()))).isEqualTo("FOREIGN_LICENCE_EXISTS");
        Map<String, Object> exchange = Map.of("region", "IT_TX", "customerCategory", "CITIZEN");
        assertThat(code(postAs("/v1/foreign-licences/" + untranslated + "/exchanges", exchange, counter())))
                .isEqualTo("TRANSLATION_REQUIRED");

        // DL-055 visitor permit on the foreign licence, before it is exchanged
        JsonNode visitor = expect(postAs("/v1/temporary-permits", Map.of("kind", "VISITOR", "trafficFileNumber", file,
                "region", "IT_TX", "foreignLicenceId", untranslated), counter()), 201);
        assertThat(code(postAs("/v1/temporary-permits", Map.of("kind", "VISITOR", "trafficFileNumber", file,
                "region", "IT_TX", "foreignLicenceId", untranslated), counter()))).isEqualTo("REQUEST_OPEN");
        paid(visitor);
        JsonNode permit = body(getAs("/v1/temporary-permits?trafficFileNumber=" + file, counter())).get("items").get(0);
        assertThat(permit.get("validUntil").asString()).isEqualTo(TODAY.plusDays(29).toString());
        assertThat(code(postAs("/v1/temporary-permits", Map.of("kind", "VISITOR", "trafficFileNumber", file,
                "region", "IT_TX", "foreignLicenceId", untranslated), counter()))).isEqualTo("PERMIT_EXISTS");
        assertThat(expect(postAs("/v1/temporary-permits/" + permit.get("id").asString() + "/cancellation",
                Map.of("reason", "left the country"), counter()), 200).get("status").asString()).isEqualTo("CANCELLED");

        foreign.put("foreignNumber", "F2-" + file);
        foreign.put("translationDocumentId", "DOC-T-" + file);
        String translated = expect(postAs("/v1/foreign-licences", foreign, counter()), 201).get("id").asString();
        JsonNode done = paid(expect(postAs("/v1/foreign-licences/" + translated + "/exchanges", exchange, counter()),
                201));
        String licenceId = done.get("licenceId").asString();
        assertThat(body(getAs("/v1/licences/" + licenceId, counter())).get("classes").get(0).get("classCode")
                .asString()).isEqualTo(classCode);
        assertThat(code(postAs("/v1/foreign-licences/" + translated + "/exchanges", exchange, counter())))
                .isEqualTo("FOREIGN_LICENCE_USED");
        assertThat(body(getAs("/v1/foreign-licences?trafficFileNumber=" + file, counter())).get("items")
                .findValuesAsString("status")).contains("EXCHANGED", "RECORDED");
        assertThat(code(getAs("/v1/foreign-licences?countryCode=" + country + "&limit=0", counter())))
                .isIn("PAGE_INVALID", "VALIDATION_FAILED");

        Map<String, Object> other = new HashMap<>(foreign);
        other.put("trafficFileNumber", newFile());
        other.put("countryCode", testCountry);
        other.put("foreignNumber", "F3-" + file);
        String testOnly = expect(postAs("/v1/foreign-licences", other, counter()), 201).get("id").asString();
        assertThat(code(postAs("/v1/foreign-licences/" + testOnly + "/exchanges", exchange, counter())))
                .isEqualTo("TEST_REQUIRED");
        other.put("countryCode", "ZZQ");
        other.put("foreignNumber", "F4-" + file);
        String noRule = expect(postAs("/v1/foreign-licences", other, counter()), 201).get("id").asString();
        assertThat(code(postAs("/v1/foreign-licences/" + noRule + "/exchanges", exchange, counter())))
                .isEqualTo("COUNTRY_RULE_MISSING");

        // DL-055 card-pending permit and DL-056 international permit on the exchanged licence
        JsonNode pending = expect(postAs("/v1/temporary-permits", Map.of("kind", "CARD_PENDING", "trafficFileNumber",
                file, "region", "IT_TX", "licenceId", licenceId), counter()), 201);
        paid(pending);
        String idps = "/v1/licences/" + licenceId + "/international-permits";
        assertThat(code(postAs(idps, Map.of("printer", "NOT_LISTED"), counter()))).isEqualTo("PRINTER_NOT_APPROVED");
        paid(expect(postAs(idps, Map.of("printer", "AUTO_CLUB"), counter()), 201));
        assertThat(code(postAs(idps, Map.of(), counter()))).isEqualTo("IDP_EXISTS");
        String idp = body(getAs(idps, counter())).get("items").get(0).get("id").asString();
        assertThat(code(postAs("/v1/international-permits/" + idp + "/printed", Map.of("printer", "CENTRE"),
                counter()))).isEqualTo("PRINTER_MISMATCH");
        assertThat(expect(postAs("/v1/international-permits/" + idp + "/printed", Map.of("printer", "AUTO_CLUB"),
                counter()), 200).get("status").asString()).isEqualTo("PRINTED");
    }
}

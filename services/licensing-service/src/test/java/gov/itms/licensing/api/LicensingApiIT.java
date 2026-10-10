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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
        "itms.security.authorisation-source=token-claims"})
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
}

package gov.itms.rules.api;

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
 * End-to-end slice test of rules: HTTP → security → service → PostgreSQL (V1 guards) → audit outbox, with every request
 * and response validated against rules-service.v1.yaml. Tests share one database, so each uses its own rule set code.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("CC-050")
class RulesApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final AtomicInteger SETS = new AtomicInteger();
    private static OpenApiInteractionValidator contract;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/rules-service.v1.yaml").build();
    }

    private static RequestPostProcessor maker() {
        return user("u-maker", "maker", "RULE_MAKER", "rules.version.submit", "rules.version.read",
                "rules.version.test");
    }

    private static RequestPostProcessor checker() {
        return user("u-checker", "checker", "RULE_CHECKER", "rules.version.approve", "rules.version.read",
                "rules.version.test", "rules.decision.read");
    }

    private static RequestPostProcessor evaluator() {
        return user("svc-licensing", "licensing", "RULE_EVALUATOR", "rules.evaluate");
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
        boolean idempotent = path.startsWith("/v1/rule-versions") && !path.endsWith("/test-runs");
        return call(post(path), Request.Method.POST, path, body, who, idempotent ? "it-" + UUID.randomUUID() : null);
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

    private static String newRuleSet() {
        return "RS" + SETS.incrementAndGet() + "_" + Long.toString(System.nanoTime() % 1_000_000, 36).toUpperCase();
    }

    /** A FIRST-hit table: eligible when category B and age >= {@code age}; otherwise the default (not eligible). */
    private static Map<String, Object> table(int age) {
        return Map.of(
                "hitPolicy", "FIRST",
                "inputs", List.of(Map.of("name", "category", "type", "TEXT"), Map.of("name", "age", "type", "NUMBER")),
                "outputs", List.of(Map.of("name", "eligible", "type", "BOOLEAN")),
                "rules", List.of(Map.of(
                        "when", Map.of("category", Map.of("op", "EQ", "values", List.of("B")),
                                "age", Map.of("op", "GE", "values", List.of(age))),
                        "then", Map.of("eligible", true),
                        "description", "car from " + age)),
                "defaults", Map.of("eligible", false));
    }

    private static Map<String, Object> version(String ruleSet, String region, int age, LocalDate from) {
        Map<String, Object> m = new HashMap<>();
        m.put("ruleSetCode", ruleSet);
        m.put("nameNl", "الحد الأدنى للعمر");
        m.put("nameEn", "Minimum age");
        m.put("region", region);
        m.put("table", table(age));
        m.put("effectiveFrom", from.toString());
        m.put("reason", "new rule version");
        return m;
    }

    private String submitted(Map<String, Object> request) throws Exception {
        MvcResult r = postAs("/v1/rule-versions", request, maker());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("id").asString();
    }

    private String approved(Map<String, Object> request) throws Exception {
        String id = submitted(request);
        MvcResult r = postAs("/v1/rule-versions/" + id + "/approvals", Map.of("reason", "checked"), checker());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return id;
    }

    private MvcResult evaluate(String ruleSet, String region, Map<String, Object> inputs) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("region", region);
        request.put("inputs", inputs);
        request.put("reference", "APP-" + ruleSet);
        return postAs("/v1/rule-sets/" + ruleSet + "/evaluations", request, evaluator());
    }

    @Test
    @Tag("CC-051")
    @Tag("CC-002")
    @DisplayName("maker submits, the maker cannot approve, a checker approves once; audited; the guard freezes the row")
    void makerChecker() throws Exception {
        String ruleSet = newRuleSet();
        String id = submitted(version(ruleSet, null, 18, TODAY));
        JsonNode pending = body(getAs("/v1/rule-versions/" + id, maker()));
        assertThat(pending.get("status").asString()).isEqualTo("PENDING");
        assertThat(pending.get("versionNo").asInt()).isEqualTo(1);

        MvcResult self = postAs("/v1/rule-versions/" + id + "/approvals", Map.of(),
                user("u-maker", "maker", "BOTH", "rules.version.approve"));
        assertThat(self.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(self)).isEqualTo("SAME_MAKER_CHECKER");
        assertThat(postAs("/v1/rule-versions/" + id + "/approvals", Map.of(), maker()).getResponse().getStatus())
                .as("no approve permission").isEqualTo(403);

        MvcResult ok = postAs("/v1/rule-versions/" + id + "/approvals", Map.of("reason", "checked"), checker());
        assertThat(body(ok).get("status").asString()).isEqualTo("APPROVED");
        assertThat(body(ok).get("decided").get("actorId").asString()).isEqualTo("u-checker");
        assertThat(code(postAs("/v1/rule-versions/" + id + "/rejections", Map.of("reason", "too late"), checker())))
                .isEqualTo("RULE_VERSION_DECIDED");

        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :id and action in "
                + "('RULE_VERSION_SUBMITTED', 'RULE_VERSION_APPROVED')").param("id", id).query(Long.class).single())
                .isEqualTo(2);
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.rule.version-approved' "
                + "and subject = :s").param("s", ruleSet).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> db.sql("update rule_version set name_en = 'x' where id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("never changes");
        assertThatThrownBy(() -> db.sql("update rule_version set status = 'REJECTED' where id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from rule_version where id = cast(:id as uuid)").param("id", id)
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("never deleted");
    }

    @Test
    @Tag("CC-051")
    @DisplayName("a successor ends its predecessor; an earlier-starting version overlaps; a past start is refused")
    void successorsAndOverlap() throws Exception {
        String ruleSet = newRuleSet();
        String first = approved(version(ruleSet, null, 18, TODAY.plusDays(1)));
        approved(version(ruleSet, null, 17, TODAY.plusDays(30)));
        assertThat(body(getAs("/v1/rule-versions/" + first, maker())).get("effectiveTo").asString())
                .isEqualTo(TODAY.plusDays(29).toString());

        String earlier = submitted(version(ruleSet, null, 16, TODAY.plusDays(10)));
        assertThat(code(postAs("/v1/rule-versions/" + earlier + "/approvals", Map.of(), checker())))
                .isEqualTo("RULE_VERSION_OVERLAP");
        assertThat(code(postAs("/v1/rule-versions", version(ruleSet, null, 18, TODAY.minusDays(1)), maker())))
                .isEqualTo("RULE_START_IN_PAST");

        JsonNode list = body(getAs("/v1/rule-versions?ruleSetCode=" + ruleSet, maker()));
        assertThat(list.get("items")).hasSize(3);
        assertThat(body(getAs("/v1/rule-versions?ruleSetCode=" + ruleSet + "&status=PENDING", maker()))
                .get("items")).hasSize(1);
    }

    @Test
    @Tag("CC-051")
    @DisplayName("the maker withdraws their own pending version; a checker rejects another")
    void withdrawAndReject() throws Exception {
        String ruleSet = newRuleSet();
        String mine = submitted(version(ruleSet, null, 18, TODAY));
        MvcResult withdrawn = postAs("/v1/rule-versions/" + mine + "/rejections",
                Map.of("reason", "submitted by mistake"), maker());
        assertThat(body(withdrawn).get("status").asString()).isEqualTo("REJECTED");
        String other = submitted(version(ruleSet, null, 18, TODAY));
        postAs("/v1/rule-versions/" + other + "/rejections", Map.of("reason", "table is wrong"), checker());
        assertThat(db.sql("select action from audit_outbox where entity_id in (:a, :b) and action in "
                + "('RULE_VERSION_REJECTED', 'RULE_VERSION_WITHDRAWN') order by action").param("a", mine)
                .param("b", other).query(String.class).list())
                .containsExactly("RULE_VERSION_REJECTED", "RULE_VERSION_WITHDRAWN");
    }

    @Test
    @Tag("CC-052")
    @DisplayName("a pending version is test-run against sample cases; nothing is logged as a decision")
    void testRuns() throws Exception {
        String ruleSet = newRuleSet();
        String id = submitted(version(ruleSet, null, 18, TODAY));
        Map<String, Object> run = Map.of("cases", List.of(
                Map.of("inputs", Map.of("category", "B", "age", 18), "expected", Map.of("eligible", true)),
                Map.of("inputs", Map.of("category", "B", "age", 17), "expected", Map.of("eligible", true)),
                Map.of("inputs", Map.of("colour", "red"))));
        MvcResult r = postAs("/v1/rule-versions/" + id + "/test-runs", run, maker());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode result = body(r);
        assertThat(result.get("cases").asInt()).isEqualTo(3);
        assertThat(result.get("passed").asInt()).isEqualTo(1);
        assertThat(result.get("failed").asInt()).isEqualTo(1);
        assertThat(result.get("results").get(2).get("error").asString()).isEqualTo("RULE_INPUT_UNKNOWN");
        assertThat(db.sql("select count(*) from rule_decision where rule_set_code = :s").param("s", ruleSet)
                .query(Long.class).single()).isZero();
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :id and action = 'RULE_VERSION_TESTED'")
                .param("id", id).query(Long.class).single()).isEqualTo(1);
        assertThat(postAs("/v1/rule-versions/" + id + "/test-runs", run, evaluator()).getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    @Tag("CC-051")
    @Tag("CC-053")
    @DisplayName("evaluation picks the regional variant over the national one, logs the decision and reads it back")
    void evaluationAndDecisionLog() throws Exception {
        String ruleSet = newRuleSet();
        String national = approved(version(ruleSet, null, 18, TODAY));
        String north = approved(version(ruleSet, "NORTH", 21, TODAY));

        MvcResult inNorth = evaluate(ruleSet, "NORTH", Map.of("category", "B", "age", 19));
        assertThat(inNorth.getResponse().getStatus()).as(inNorth.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(inNorth).get("versionId").asString()).isEqualTo(north);
        assertThat(body(inNorth).get("outputs").get(0).get("eligible").asBoolean()).isFalse();
        MvcResult inSouth = evaluate(ruleSet, "SOUTH", Map.of("category", "B", "age", 19));
        assertThat(body(inSouth).get("versionId").asString()).isEqualTo(national);
        assertThat(body(inSouth).get("matchedRules").get(0).asInt()).isEqualTo(1);
        assertThat(body(inSouth).get("outputs").get(0).get("eligible").asBoolean()).isTrue();
        assertThat(code(evaluate(ruleSet, null, Map.of("colour", "red")))).isEqualTo("RULE_INPUT_UNKNOWN");
        assertThat(code(evaluate(newRuleSet(), null, Map.of()))).isEqualTo("RULE_NOT_IN_FORCE");

        String decisionId = body(inSouth).get("id").asString();
        JsonNode read = body(getAs("/v1/rule-decisions/" + decisionId, checker()));
        assertThat(read.get("actorId").asString()).isEqualTo("svc-licensing");
        assertThat(read.get("reference").asString()).isEqualTo("APP-" + ruleSet);
        assertThat(read.get("inputs").get("age").asInt()).isEqualTo(19);
        assertThat(body(getAs("/v1/rule-decisions?ruleSetCode=" + ruleSet, checker())).get("items")).hasSize(2);
        assertThat(getAs("/v1/rule-decisions/" + UUID.randomUUID(), checker()).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(getAs("/v1/rule-decisions/" + decisionId, maker()).getResponse().getStatus()).isEqualTo(403);
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :id and action = 'RULE_DECISION_VIEWED'")
                .param("id", decisionId).query(Long.class).single()).isEqualTo(1);

        assertThatThrownBy(() -> db.sql("update rule_decision set reference = 'x' where id = cast(:id as uuid)")
                .param("id", decisionId).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("delete from rule_decision where id = cast(:id as uuid)")
                .param("id", decisionId).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("ADR-032: a retried submission with the same Idempotency-Key replays the stored response")
    void idempotentSubmit() throws Exception {
        Map<String, Object> request = version(newRuleSet(), null, 18, TODAY);
        String key = "it-" + UUID.randomUUID();
        MvcResult first = call(post("/v1/rule-versions"), Request.Method.POST, "/v1/rule-versions", request, maker(),
                key);
        MvcResult retry = call(post("/v1/rule-versions"), Request.Method.POST, "/v1/rule-versions", request, maker(),
                key);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(retry).get("id").asString()).isEqualTo(body(first).get("id").asString());
        assertThat(db.sql("select count(*) from rule_version where rule_set_code = :s")
                .param("s", request.get("ruleSetCode")).query(Long.class).single()).isEqualTo(1);
    }
}

package gov.itms.transaction.api;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * End-to-end slice test: HTTP → security → service → PostgreSQL (Flyway schema, triggers, constraints) → audit outbox.
 * Every request and response is validated against transaction-service.v1.yaml (API contract test). Tests share one
 * database, so each uses its own service codes.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        // Grants from token claims: this test covers the catalogue, not where grants come from (ADR-022 is covered
        // by the platform tests and by iam-service).
        "itms.security.authorisation-source=token-claims",
        // Never called here: submission (and so eligibility, CC-032) is covered by TransactionApiIT.
        "itms.clients.eligibility.base-url=http://127.0.0.1:9",
        "itms.clients.eligibility.token-uri=http://127.0.0.1:9/token",
        "itms.clients.eligibility.client-secret=unused",
        "itms.clients.traffic-files.base-url=http://127.0.0.1:9"})
@AutoConfigureMockMvc
@Testcontainers
class CatalogueApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static OpenApiInteractionValidator contract;
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/transaction-service.v1.yaml").build();
    }

    // ------------------------------------------------------------------ actors (permissions as iam would grant)

    private static RequestPostProcessor maker() {
        return user("u-maker", "catalogue.maker", "catalogue.change.submit", "catalogue.change.read");
    }

    private static RequestPostProcessor checker() {
        return user("u-checker", "catalogue.checker", "catalogue.change.read", "catalogue.change.approve",
                "catalogue.history.read");
    }

    /** Holds both maker and checker permissions: may submit and approve, but never their own change. */
    private static RequestPostProcessor both() {
        return user("u-both", "catalogue.both", "catalogue.change.submit", "catalogue.change.read",
                "catalogue.change.approve");
    }

    /** Signed in, no permissions (for example a customer through a channel). */
    private static RequestPostProcessor citizen() {
        return user("u-citizen", "citizen");
    }

    private static RequestPostProcessor user(String sub, String username, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", username)
                .claim("roles", List.of(username.toUpperCase().replace('.', '_')))
                .claim("permissions", Arrays.asList(permissions)));
    }

    // ------------------------------------------------------------------ helpers with contract validation

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        builder.with(who).header("X-Channel", "BACKOFFICE").header("X-Correlation-Id", "txn-it-correlation")
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        if (payload != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(payload);
        }
        MvcResult result = mvc.perform(builder).andReturn();

        int qs = path.indexOf('?');
        String requestPath = qs > 0 ? path.substring(0, qs) : path;
        SimpleRequest.Builder req = new SimpleRequest.Builder(method, requestPath).withHeader("Authorization", "Bearer t");
        if (qs > 0) {
            for (String pair : path.substring(qs + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                req.withQueryParam(kv[0], kv[1]);
            }
        }
        if (payload != null) {
            req.withContentType("application/json").withBody(payload);
        }
        SimpleResponse response = SimpleResponse.Builder
                .status(result.getResponse().getStatus())
                .withContentType(result.getResponse().getContentType())
                .withBody(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .build();
        // Responses are always checked; requests only on success paths (negative tests send invalid requests).
        ValidationReport report = result.getResponse().getStatus() < 400
                ? contract.validate(req.build(), response)
                : contract.validateResponse(requestPath, method, response);
        assertThat(report.getMessages()).as("OpenAPI contract for %s %s -> %d", method, requestPath,
                result.getResponse().getStatus()).isEmpty();
        return result;
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult getAs(String path, RequestPostProcessor who) throws Exception {
        return call(get(path), Request.Method.GET, path, null, who);
    }

    private MvcResult postAs(String path, Object body, RequestPostProcessor who) throws Exception {
        return call(post(path), Request.Method.POST, path, body, who);
    }

    private static Map<String, Object> spec(String code, List<String> regions, List<String> channels) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("code", code);
        s.put("name", Map.of("nl", "خدمة " + code, "en", "Service " + code));
        s.put("module", "VEHICLE");
        s.put("customerTypes", List.of("INDIVIDUAL", "COMPANY"));
        s.put("channels", channels);
        s.put("requiredDocuments", List.of("INSURANCE_CERT"));
        s.put("feeCode", "VL_RENEW_FEE");
        s.put("ruleSetCode", null);
        s.put("workflowCode", null);
        s.put("sla", "P5D");
        s.put("regions", regions);
        s.put("anyRegion", true);
        s.put("allowsConcurrentOpen", false);
        return s;
    }

    private static Map<String, Object> create(Map<String, Object> spec) {
        return Map.of("changeType", "CREATE_SERVICE", "serviceCode", spec.get("code"), "service", spec,
                "reason", "integration test");
    }

    private static Map<String, Object> addRule(String code, String region, String channel, boolean enabled,
                                               LocalDate from, LocalDate to) {
        Map<String, Object> rule = new HashMap<>();
        rule.put("region", region);
        rule.put("channel", channel);
        rule.put("enabled", enabled);
        rule.put("effectiveFrom", from.toString());
        if (to != null) {
            rule.put("effectiveTo", to.toString());
        }
        return Map.of("changeType", "ADD_AVAILABILITY_RULE", "serviceCode", code, "rule", rule,
                "reason", "integration test rule");
    }

    private String submitAndApprove(Map<String, Object> change) throws Exception {
        MvcResult submitted = postAs("/v1/catalogue-changes", change, maker());
        assertThat(submitted.getResponse().getStatus()).as(submitted.getResponse().getContentAsString()).isEqualTo(201);
        String id = body(submitted).get("id").asString();
        MvcResult approved = postAs("/v1/catalogue-changes/" + id + "/approvals", null, checker());
        assertThat(approved.getResponse().getStatus()).as(approved.getResponse().getContentAsString()).isEqualTo(200);
        return id;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Tag("CC-020")
    @Tag("CC-114")
    @Tag("CC-120")
    @Tag("DAT-002")
    @DisplayName("CC-020/CC-114/CC-120/DAT-002: a service is created and updated through maker-checker, with history and audit")
    void serviceLifecycle() throws Exception {
        MvcResult submitted = postAs("/v1/catalogue-changes", create(spec("IT_LIFE", List.of("*"),
                List.of("PORTAL", "BACKOFFICE"))), maker());
        assertThat(submitted.getResponse().getStatus()).isEqualTo(201);
        String id = body(submitted).get("id").asString();
        assertThat(submitted.getResponse().getHeader("Location")).isEqualTo("/v1/catalogue-changes/" + id);
        assertThat(body(submitted).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(getAs("/v1/services/IT_LIFE", citizen()).getResponse().getStatus()).isEqualTo(404);

        MvcResult approved = postAs("/v1/catalogue-changes/" + id + "/approvals", null, checker());
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(approved).get("checker").get("id").asString()).isEqualTo("u-checker");

        JsonNode service = body(getAs("/v1/services/IT_LIFE", citizen()));
        assertThat(service.get("version").asLong()).isEqualTo(1);
        assertThat(service.get("definition").get("sla").asString()).isEqualTo("P5D");
        assertThat(service.get("definition").get("name").get("nl").asString()).isEqualTo("خدمة IT_LIFE");
        assertThat(body(getAs("/v1/services", citizen())).get("items")).anySatisfy(
                n -> assertThat(n.get("definition").get("code").asString()).isEqualTo("IT_LIFE"));

        Map<String, Object> updated = spec("IT_LIFE", List.of("R1", "R2"), List.of("PORTAL"));
        submitAndApprove(Map.of("changeType", "UPDATE_SERVICE", "serviceCode", "IT_LIFE", "service", updated,
                "baseVersion", 1, "reason", "offer in two regions"));
        JsonNode history = body(getAs("/v1/services/IT_LIFE/history", checker())).get("items");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("version").asLong()).isEqualTo(1);
        assertThat(history.get(0).get("validTo").isNull()).isFalse();
        assertThat(history.get(1).get("definition").get("regions").toString()).isEqualTo("[\"R1\",\"R2\"]");
        assertThat(history.get(1).get("validTo").isNull()).isTrue();

        List<String> actions = db.sql("select action from audit_outbox where entity_id in (:ids) order by occurred_at, action")
                .param("ids", List.of(id, "IT_LIFE")).query(String.class).list();
        assertThat(actions).contains("CATALOGUE_CHANGE_SUBMITTED", "CATALOGUE_CHANGE_APPROVED",
                "SERVICE_DEFINITION_CREATED", "SERVICE_DEFINITION_UPDATED");

        // A stale update (prepared against version 1) is refused.
        MvcResult stale = postAs("/v1/catalogue-changes", Map.of("changeType", "UPDATE_SERVICE",
                "serviceCode", "IT_LIFE", "service", updated, "baseVersion", 1, "reason", "stale"), maker());
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(stale).get("code").asString()).isEqualTo("STALE_CHANGE");
    }

    @Test
    @Tag("CC-026")
    @Tag("CC-112")
    @DisplayName("CC-026/CC-112: availability per region and channel with effective dates")
    void availability() throws Exception {
        submitAndApprove(create(spec("IT_AVAIL", List.of("R1", "R2"), List.of("PORTAL", "KIOSK"))));
        JsonNode offered = body(getAs("/v1/services/IT_AVAIL/availability?region=R1&channel=KIOSK", citizen()));
        assertThat(offered.get("available").asBoolean()).isTrue();
        assertThat(offered.get("reason").asString()).isEqualTo("OFFERED");
        assertThat(offered.get("date").asString()).isEqualTo(TODAY.toString());
        assertThat(body(getAs("/v1/services/IT_AVAIL/availability?region=R3&channel=KIOSK", citizen()))
                .get("reason").asString()).isEqualTo("NOT_OFFERED_IN_REGION");

        submitAndApprove(addRule("IT_AVAIL", "*", "KIOSK", false, TODAY, null));
        submitAndApprove(addRule("IT_AVAIL", "R1", "KIOSK", true, TODAY, TODAY.plusDays(2)));
        JsonNode rules = body(getAs("/v1/services/IT_AVAIL/availability-rules", citizen())).get("items");
        assertThat(rules).hasSize(2);

        assertThat(body(getAs("/v1/services/IT_AVAIL/availability?region=R2&channel=KIOSK", citizen()))
                .get("reason").asString()).isEqualTo("DISABLED_BY_RULE");
        assertThat(body(getAs("/v1/services/IT_AVAIL/availability?region=R1&channel=KIOSK", citizen()))
                .get("reason").asString()).isEqualTo("ENABLED_BY_RULE");
        assertThat(body(getAs("/v1/services/IT_AVAIL/availability?region=R1&channel=KIOSK&date="
                + TODAY.plusDays(3), citizen())).get("available").asBoolean()).isFalse();

        // Listing by region and channel.
        assertThat(body(getAs("/v1/services?region=R2&channel=KIOSK", citizen())).get("items"))
                .noneSatisfy(n -> assertThat(n.get("definition").get("code").asString()).isEqualTo("IT_AVAIL"));
        assertThat(body(getAs("/v1/services?region=R1&channel=KIOSK", citizen())).get("items"))
                .anySatisfy(n -> assertThat(n.get("definition").get("code").asString()).isEqualTo("IT_AVAIL"));
        MvcResult half = getAs("/v1/services?region=R1", citizen());
        assertThat(half.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(half).get("code").asString()).isEqualTo("AVAILABILITY_QUERY_INVALID");

        // End the kiosk-off rule today (its last day): from tomorrow kiosks in R2 are offered again.
        String ruleId = null;
        for (JsonNode r : rules) {
            if ("*".equals(r.get("region").asString())) {
                ruleId = r.get("id").asString();
            }
        }
        submitAndApprove(Map.of("changeType", "END_AVAILABILITY_RULE", "serviceCode", "IT_AVAIL", "ruleId", ruleId,
                "endDate", TODAY.toString(), "reason", "kiosks back"));
        assertThat(body(getAs("/v1/services/IT_AVAIL/availability?region=R2&channel=KIOSK&date="
                + TODAY.plusDays(1), citizen())).get("reason").asString()).isEqualTo("OFFERED");
        assertThat(db.sql("select action from audit_outbox where entity_id = :id").param("id", ruleId)
                .query(String.class).list()).containsExactlyInAnyOrder("AVAILABILITY_RULE_ADDED",
                "AVAILABILITY_RULE_ENDED");

        // Overlap and rules outside the offer are refused.
        MvcResult overlap = postAs("/v1/catalogue-changes", addRule("IT_AVAIL", "R1", "KIOSK", false,
                TODAY.plusDays(1), null), maker());
        assertThat(overlap.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(overlap).get("code").asString()).isEqualTo("RULE_OVERLAP");
        MvcResult outside = postAs("/v1/catalogue-changes", addRule("IT_AVAIL", "R9", "*", false, TODAY, null),
                maker());
        assertThat(outside.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(outside).get("code").asString()).isEqualTo("RULE_OUTSIDE_OFFER");
    }

    @Test
    @Tag("CC-001")
    @Tag("CC-002")
    @Tag("CC-114")
    @DisplayName("CC-001/CC-002/CC-114: permissions per operation; nobody approves their own change")
    void authorisationAndSegregation() throws Exception {
        assertThat(mvc.perform(get("/v1/services")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(getAs("/v1/services", citizen()).getResponse().getStatus()).isEqualTo(200);
        assertThat(getAs("/v1/catalogue-changes", citizen()).getResponse().getStatus()).isEqualTo(403);
        assertThat(postAs("/v1/catalogue-changes", create(spec("IT_AUTH", List.of("*"), List.of("APP"))), citizen())
                .getResponse().getStatus()).isEqualTo(403);

        MvcResult mine = postAs("/v1/catalogue-changes", create(spec("IT_AUTH", List.of("*"), List.of("APP"))),
                both());
        String id = body(mine).get("id").asString();
        assertThat(postAs("/v1/catalogue-changes/" + id + "/approvals", null, maker()).getResponse().getStatus())
                .isEqualTo(403);
        MvcResult self = postAs("/v1/catalogue-changes/" + id + "/approvals", null, both());
        assertThat(self.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(self).get("code").asString()).isEqualTo("SEGREGATION_OF_DUTIES");
        MvcResult selfReject = postAs("/v1/catalogue-changes/" + id + "/rejections", Map.of("reason", "mine"),
                both());
        assertThat(body(selfReject).get("code").asString()).isEqualTo("SEGREGATION_OF_DUTIES");

        MvcResult second = postAs("/v1/catalogue-changes", create(spec("IT_AUTH", List.of("R1"), List.of("APP"))),
                maker());
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(second).get("code").asString()).isEqualTo("CHANGE_ALREADY_PENDING");

        MvcResult rejected = postAs("/v1/catalogue-changes/" + id + "/rejections", Map.of("reason", "not needed"),
                checker());
        assertThat(body(rejected).get("status").asString()).isEqualTo("REJECTED");
        assertThat(body(getAs("/v1/catalogue-changes?status=REJECTED", checker())).get("items"))
                .anySatisfy(n -> assertThat(n.get("id").asString()).isEqualTo(id));
        assertThat(getAs("/v1/catalogue-changes/" + id, maker()).getResponse().getStatus()).isEqualTo(200);
        assertThat(getAs("/v1/services/IT_AUTH/history", maker()).getResponse().getStatus()).isEqualTo(403);
        assertThat(getAs("/v1/services/IT_AUTH", citizen()).getResponse().getStatus()).isEqualTo(404);
        MvcResult again = postAs("/v1/catalogue-changes/" + id + "/approvals", null, checker());
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(again).get("code").asString()).isEqualTo("CHANGE_NOT_PENDING");
    }

    @Test
    @Tag("SEC-022")
    @Tag("CH-053")
    @DisplayName("SEC-022/CH-053: invalid changes are refused with a plain-language problem")
    void validation() throws Exception {
        Map<String, Object> bad = spec("IT_BAD", List.of("*", "R1"), List.of("APP"));
        MvcResult mixed = postAs("/v1/catalogue-changes", create(bad), maker());
        assertThat(mixed.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(mixed).get("code").asString()).isEqualTo("REGIONS_ALL_MIXED");

        Map<String, Object> zeroSla = spec("IT_BAD", List.of("*"), List.of("APP"));
        zeroSla.put("sla", "P0D");
        MvcResult sla = postAs("/v1/catalogue-changes", create(zeroSla), maker());
        assertThat(sla.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(sla).get("detail").asString()).startsWith("Enter the SLA as a positive time");

        Map<String, Object> lower = spec("it_bad", List.of("*"), List.of("APP"));
        assertThat(postAs("/v1/catalogue-changes", create(lower), maker()).getResponse().getStatus()).isEqualTo(400);

        MvcResult shape = postAs("/v1/catalogue-changes", Map.of("changeType", "UPDATE_SERVICE",
                "serviceCode", "IT_BAD", "service", spec("IT_BAD", List.of("*"), List.of("APP")), "reason", "no base"),
                maker());
        assertThat(shape.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(shape).get("code").asString()).isEqualTo("CHANGE_SHAPE_INVALID");

        Map<String, Object> dupChannels = spec("IT_BAD", List.of("*"), List.of("APP", "APP"));
        MvcResult dup = postAs("/v1/catalogue-changes", create(dupChannels), maker());
        assertThat(dup.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(dup).get("code").asString()).isEqualTo("LIST_DUPLICATES");

        MvcResult arabic = mvc.perform(post("/v1/catalogue-changes").with(maker()).header("Accept-Language", "ar")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create(bad)))
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)).andReturn();
        assertThat(body(arabic).get("detail").asString()).isEqualTo("اختر جميع المناطق (*) أو قائمة مناطق، وليس الاثنين معاً.");

        assertThat(getAs("/v1/services/IT_NOPE/availability?region=R1&channel=APP", citizen()).getResponse()
                .getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/v1/services/IT_NOPE/availability").param("region", "R1")
                .param("channel", "APP ").with(citizen())).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get("/v1/services/IT_NOPE/availability").param("region", "R1").with(citizen()))
                .andReturn().getResponse().getStatus()).isEqualTo(400);

        // Unknown fields are refused (additionalProperties: false in the contract).
        Map<String, Object> extra = new HashMap<>(create(spec("IT_EXTRA", List.of("*"), List.of("APP"))));
        extra.put("approvedBy", "u-maker");
        assertThat(postAs("/v1/catalogue-changes", extra, maker()).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @Tag("DAT-002")
    @Tag("CC-114")
    @Tag("CC-002")
    @DisplayName("DAT-002/CC-114/CC-002: the database refuses deletes, history edits, rule overlaps and self-approval")
    void databaseGuards() throws Exception {
        submitAndApprove(create(spec("IT_DB", List.of("*"), List.of("APP"))));
        submitAndApprove(addRule("IT_DB", "R1", "APP", false, TODAY, TODAY.plusDays(5)));

        assertThatThrownBy(() -> db.sql("delete from service_definition where code = 'IT_DB'").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("update service_definition_history set sla = 'P9D' where code = 'IT_DB'")
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("update service_definition set code = 'IT_DB2', version = version + 1"
                + " where code = 'IT_DB'").update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("update service_definition set sla = 'P9D' where code = 'IT_DB'").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("""
                insert into availability_rule (id, service_code, region, channel, enabled, effective_from, change_id,
                       created_at, created_by)
                values (gen_random_uuid(), 'IT_DB', 'R1', 'APP', true, current_date + 3, gen_random_uuid(), now(), 'x')""")
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("update availability_rule set enabled = true where service_code = 'IT_DB'")
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("update availability_rule set effective_to = effective_to + 10,"
                + " ended_by_change = gen_random_uuid() where service_code = 'IT_DB'").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from availability_rule where service_code = 'IT_DB'").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("update catalogue_change set reason = 'edited' where service_code = 'IT_DB'")
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("""
                insert into catalogue_change (id, change_type, status, service_code, payload, reason, maker_id,
                       submitted_at, checker_id, decided_at)
                values (gen_random_uuid(), 'CREATE_SERVICE', 'APPROVED', 'IT_DB3', '{}', 'self approved', 'u-x', now(),
                        'u-x', now())""").update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("""
                insert into catalogue_change (id, change_type, status, service_code, payload, reason, maker_id,
                       submitted_at)
                values (gen_random_uuid(), 'CREATE_SERVICE', 'PENDING_APPROVAL', 'IT_DB4', '{}', E'\t\t\t', 'u-x', now())""")
                .update()).isInstanceOf(DataAccessException.class);
    }
}

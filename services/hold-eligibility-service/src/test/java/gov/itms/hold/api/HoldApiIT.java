package gov.itms.hold.api;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * End-to-end slice test of holds: HTTP → security → service → PostgreSQL (V1 guards and temporal history) → audit
 * outbox. Every request and response is validated against hold-eligibility-service.v1.yaml. Tests share one database,
 * so each uses its own subjects.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
class HoldApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static OpenApiInteractionValidator contract;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/hold-eligibility-service.v1.yaml").build();
    }

    // ------------------------------------------------------------------ actors (token-claims mode: national scope)

    private static RequestPostProcessor officer() {
        return user("u-officer", "officer", "HOLD_OFFICER", "hold.place", "hold.lift", "hold.read");
    }

    private static RequestPostProcessor courtClerk() {
        return user("u-clerk", "clerk", "COURT_CLERK", "hold.lift", "hold.read");
    }

    private static RequestPostProcessor reader() {
        return user("u-reader", "reader", "HOLD_READER", "hold.read");
    }

    private static RequestPostProcessor user(String sub, String username, String role, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", username)
                .claim("roles", List.of(role))
                .claim("permissions", Arrays.asList(permissions)));
    }

    // ------------------------------------------------------------------ helpers with contract validation

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who, String channel, String key) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        builder.with(who).header("X-Correlation-Id", "hold-it-holds")
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        if (channel != null) {
            builder.header("X-Channel", channel);
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (payload != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(payload);
        }
        MvcResult result = mvc.perform(builder).andReturn();

        int qs = path.indexOf('?');
        String requestPath = qs > 0 ? path.substring(0, qs) : path;
        SimpleRequest.Builder req = new SimpleRequest.Builder(method, requestPath)
                .withHeader("Authorization", "Bearer t");
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
        SimpleResponse response = SimpleResponse.Builder
                .status(result.getResponse().getStatus())
                .withContentType(result.getResponse().getContentType())
                .withBody(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .build();
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
        return call(get(path), Request.Method.GET, path, null, who, "BACKOFFICE", null);
    }

    private MvcResult postAs(String path, Object body, RequestPostProcessor who) throws Exception {
        return call(post(path), Request.Method.POST, path, body, who, "BACKOFFICE", "it-" + UUID.randomUUID());
    }

    private static Map<String, Object> hold(String vin, String module) {
        Map<String, Object> m = new HashMap<>();
        m.put("subject", Map.of("type", "VEHICLE", "id", vin));
        m.put("holdType", "UNPAID_FINES");
        m.put("module", module);
        m.put("source", "VIOLATIONS");
        m.put("reason", "unpaid fines on the vehicle");
        m.put("blockedServices", List.of("VL_RENEW", "VL_TRANSFER"));
        m.put("liftableByRoles", List.of("HOLD_OFFICER"));
        return m;
    }

    private String placeOk(Map<String, Object> request) throws Exception {
        MvcResult r = postAs("/v1/holds", request, officer());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("id").asString();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).get("code").asString();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Tag("CC-030")
    @Tag("CC-120")
    @Tag("DAT-002")
    @DisplayName("CC-030: place, read, list and lift a hold; every version kept (DAT-002) and every step audited (CC-120)")
    void lifecycle() throws Exception {
        Map<String, Object> request = hold("VIN-LIFE", "VIOLATIONS");
        request.put("sourceRef", "FINE/2026:0001");
        request.put("endDate", TODAY.plusDays(30).toString());
        MvcResult placed = postAs("/v1/holds", request, officer());
        assertThat(placed.getResponse().getStatus()).isEqualTo(201);
        JsonNode h = body(placed);
        String id = h.get("id").asString();
        assertThat(placed.getResponse().getHeader("Location")).isEqualTo("/v1/holds/" + id);
        assertThat(h.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(h.get("inForce").asBoolean()).isTrue();
        assertThat(h.get("startDate").asString()).isEqualTo(TODAY.toString());
        assertThat(h.get("endDate").asString()).isEqualTo(TODAY.plusDays(30).toString());
        assertThat(h.get("sourceRef").asString()).isEqualTo("FINE/2026:0001");
        assertThat(h.get("placedBy").get("id").asString()).isEqualTo("u-officer");
        assertThat(h.get("placedChannel").asString()).isEqualTo("BACKOFFICE");
        assertThat(h.get("blockedServices")).hasSize(2);
        assertThat(h.get("liftedAt").isNull()).isTrue();

        assertThat(body(getAs("/v1/holds/" + id, reader())).get("reason").asString())
                .isEqualTo("unpaid fines on the vehicle");
        JsonNode list = body(getAs("/v1/holds?subjectType=VEHICLE&subjectId=VIN-LIFE", reader())).get("items");
        assertThat(list).hasSize(1);

        MvcResult lifted = postAs("/v1/holds/" + id + "/lifts", Map.of("reason", "fines paid"), officer());
        assertThat(lifted.getResponse().getStatus()).as(lifted.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode l = body(lifted);
        assertThat(l.get("status").asString()).isEqualTo("LIFTED");
        assertThat(l.get("inForce").asBoolean()).isFalse();
        assertThat(l.get("liftReason").asString()).isEqualTo("fines paid");
        assertThat(l.get("liftedBy").get("id").asString()).isEqualTo("u-officer");
        assertThat(l.get("version").asLong()).isEqualTo(2);
        MvcResult again = postAs("/v1/holds/" + id + "/lifts", Map.of("reason", "fines paid"), officer());
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("HOLD_NOT_ACTIVE");

        JsonNode versions = body(getAs("/v1/holds/" + id + "/history", reader())).get("items");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("hold").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(versions.get(0).get("hold").get("inForce").isNull()).isTrue();
        assertThat(versions.get(0).get("validTo").asString()).isEqualTo(versions.get(1).get("validFrom").asString());
        assertThat(versions.get(1).get("hold").get("status").asString()).isEqualTo("LIFTED");
        assertThat(versions.get(1).get("validTo").isNull()).isTrue();
        assertThat(versions.get(1).get("changedBy").asString()).isEqualTo("u-officer");

        List<String> actions = db.sql("select action from audit_outbox where entity_id = :id order by occurred_at")
                .param("id", id).query(String.class).list();
        assertThat(actions).containsExactly("HOLD_PLACED", "HOLD_VIEWED", "HOLD_LIFTED", "HOLD_HISTORY_VIEWED");
        assertThat(db.sql("select count(*) from audit_outbox where action = 'HOLDS_LISTED'"
                + " and entity_id = 'VEHICLE:VIN-LIFE'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Tag("CC-030")
    @Tag("CC-001")
    @DisplayName("CC-030/CC-001: only the hold's roles lift it; holds outside the caller's modules do not exist for them")
    void rolesAndScopes() throws Exception {
        String id = placeOk(hold("VIN-ROLE", "VIOLATIONS"));
        MvcResult clerk = postAs("/v1/holds/" + id + "/lifts", Map.of("reason", "court decision"), courtClerk());
        assertThat(clerk.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(clerk)).isEqualTo("LIFT_NOT_ALLOWED");
        assertThat(body(clerk).get("detail").asString()).contains("HOLD_OFFICER");

        Map<String, Object> court = hold("VIN-ROLE", "VIOLATIONS");
        court.put("holdType", "COURT_ORDER");
        court.put("liftableByRoles", List.of("COURT_CLERK"));
        String courtHold = placeOk(court);
        assertThat(postAs("/v1/holds/" + courtHold + "/lifts", Map.of("reason", "order revoked"), officer())
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(body(postAs("/v1/holds/" + courtHold + "/lifts", Map.of("reason", "order revoked"), courtClerk()))
                .get("status").asString()).isEqualTo("LIFTED");

        // A reader has no lift permission at all; someone without hold.read cannot list.
        assertThat(postAs("/v1/holds/" + id + "/lifts", Map.of("reason", "x paid"), reader()).getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(code(getAs("/v1/holds?subjectType=VEHICLE&subjectId=VIN-ROLE",
                user("u-none", "none", "NONE")))).isEqualTo("FORBIDDEN");
        MvcResult notMine = postAs("/v1/holds", hold("VIN-ROLE", "LICENSING"),
                user("u-lic", "lic", "HOLD_OFFICER", "hold.place"));
        assertThat(notMine.getResponse().getStatus()).as("token-claims grants are national").isEqualTo(201);
        assertThat(getAs("/v1/holds/" + UUID.randomUUID(), reader()).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @Tag("CC-030")
    @Tag("CC-033")
    @DisplayName("CC-030: input rules, the channel, a source reference used once and an Idempotency-Key retry")
    void inputRules() throws Exception {
        Map<String, Object> mixed = hold("VIN-RULE", "VIOLATIONS");
        mixed.put("blockedServices", List.of("*", "VL_RENEW"));
        assertThat(code(postAs("/v1/holds", mixed, officer()))).isEqualTo("SERVICES_ALL_MIXED");
        Map<String, Object> ended = hold("VIN-RULE", "VIOLATIONS");
        ended.put("startDate", TODAY.minusDays(10).toString());
        ended.put("endDate", TODAY.minusDays(1).toString());
        assertThat(code(postAs("/v1/holds", ended, officer()))).isEqualTo("HOLD_END_IN_PAST");
        Map<String, Object> extra = hold("VIN-RULE", "VIOLATIONS");
        extra.put("status", "LIFTED");
        assertThat(postAs("/v1/holds", extra, officer()).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> lower = hold("vin-rule", "VIOLATIONS");
        assertThat(postAs("/v1/holds", lower, officer()).getResponse().getStatus()).isEqualTo(400);

        MvcResult noChannel = call(post("/v1/holds"), Request.Method.POST, "/v1/holds", hold("VIN-RULE", "VIOLATIONS"),
                officer(), null, "it-" + UUID.randomUUID());
        assertThat(code(noChannel)).isEqualTo("CHANNEL_REQUIRED");
        MvcResult noKey = call(post("/v1/holds"), Request.Method.POST, "/v1/holds", hold("VIN-RULE", "VIOLATIONS"),
                officer(), "BACKOFFICE", null);
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(noKey)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        // The same agency reference twice is refused (CC-033 groundwork); a retry with the same key is a replay.
        Map<String, Object> court = hold("VIN-RULE", "VIOLATIONS");
        court.put("source", "COURT");
        court.put("sourceRef", "CASE-77");
        String key = "it-court-" + UUID.randomUUID();
        MvcResult first = call(post("/v1/holds"), Request.Method.POST, "/v1/holds", court, officer(), "BACKOFFICE", key);
        MvcResult retry = call(post("/v1/holds"), Request.Method.POST, "/v1/holds", court, officer(), "BACKOFFICE", key);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(retry).get("id")).isEqualTo(body(first).get("id"));
        MvcResult duplicate = postAs("/v1/holds", court, officer());
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(duplicate)).isEqualTo("DUPLICATE_SOURCE_REF");

        MvcResult arabic = mvc.perform(post("/v1/holds").with(officer()).header("Accept-Language", "ar")
                .header("Idempotency-Key", "it-ar-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(hold("VIN-RULE", "VIOLATIONS")))
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)).andReturn();
        assertThat(body(arabic).get("detail").asString()).startsWith("لا يحدد الطلب القناة");
        assertThat(db.sql("select count(*) from hold where subject_id = 'VIN-RULE'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    @Tag("CC-030")
    @Tag("DAT-002")
    @DisplayName("CC-030/DAT-002: a hold past its end date is Expired; the database refuses edits, deletes and history changes")
    void expiryAndDatabaseGuards() throws Exception {
        String id = placeOk(hold("VIN-DB", "VIOLATIONS"));
        String where = " where id = '" + id + "'";

        // Written below the application: a hold that ended yesterday (the end date is fixed once stored).
        String expired = UUID.randomUUID().toString();
        db.sql("""
                        insert into hold (id, subject_type, subject_id, hold_type, module, source, reason, start_date,
                            end_date, blocked_services, liftable_by_roles, status, placed_at, placed_by, placed_channel,
                            version)
                        values (cast(:id as uuid), 'VEHICLE', 'VIN-DB', 'IMPOUND', 'VIOLATIONS', 'VIOLATIONS',
                            'impounded', current_date - 20, current_date - 1, array['*'], array['HOLD_OFFICER'],
                            'ACTIVE', now() - interval '20 days', 'u-officer', 'BACKOFFICE', 1)""")
                .param("id", expired).update();
        JsonNode e = body(getAs("/v1/holds/" + expired, reader()));
        assertThat(e.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(e.get("inForce").asBoolean()).isFalse();
        MvcResult lift = postAs("/v1/holds/" + expired + "/lifts", Map.of("reason", "released"), officer());
        assertThat(lift.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(lift)).isEqualTo("HOLD_NOT_ACTIVE");

        assertThatThrownBy(() -> db.sql("update hold set reason = 'changed', version = version + 1" + where).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("can only be lifted once");
        assertThatThrownBy(() -> db.sql("delete from hold" + where).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never deleted");
        assertThatThrownBy(() -> db.sql("update hold set status = 'LIFTED', version = version + 1" + where).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_hold_lifted");
        assertThatThrownBy(() -> db.sql("update hold_history set changed_by = 'x'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("delete from hold_history").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("update hold set blocked_services = array['*', 'VL_RENEW'], status = 'LIFTED',"
                + " lifted_at = now(), lifted_by = 'x', lifted_channel = 'BACKOFFICE', lift_reason = 'bad',"
                + " version = version + 1" + where).update())
                .isInstanceOf(DataAccessException.class);
        assertThat(db.sql("select status from hold" + where).query(String.class).single()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ eligibility (CC-032)

    /** transaction-service's own account: ELIGIBILITY_CHECKER (iam V9). */
    private static RequestPostProcessor submissionService() {
        return user("u-txn-svc", "service-account-itms-transaction-service", "ELIGIBILITY_CHECKER",
                "eligibility.check");
    }

    private MvcResult check(String serviceCode, List<Map<String, String>> subjects, RequestPostProcessor who,
                            String language) throws Exception {
        return check(serviceCode, subjects, null, who, language);
    }

    private MvcResult check(String serviceCode, List<Map<String, String>> subjects, String transactionReference,
                            RequestPostProcessor who, String language) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("serviceCode", serviceCode);
        request.put("subjects", subjects);
        if (transactionReference != null) {
            request.put("transactionReference", transactionReference);
        }
        return call(post("/v1/eligibility-checks").header("Accept-Language", language), Request.Method.POST,
                "/v1/eligibility-checks", request, who, "PORTAL", null);
    }

    private static Map<String, String> subject(String type, String id) {
        return Map.of("type", type, "id", id);
    }

    @Test
    @Tag("CC-032")
    @Tag("CC-120")
    @DisplayName("CC-032: holds in force block the service with a customer-safe reason and next step in the "
            + "caller's language; masked types say only RESTRICTED (Q-053); every subject checked is audited")
    void eligibilityChecks() throws Exception {
        placeOk(hold("VIN-ELIG", "VIOLATIONS"));
        Map<String, Object> circular = hold("VIN-ELIG", "POLICE");
        circular.put("holdType", "POLICE_CIRCULAR");
        circular.put("source", "POLICE");
        circular.put("reason", "wanted in case 2026/77");
        circular.put("blockedServices", List.of("*"));
        placeOk(circular);
        Map<String, Object> future = hold("VIN-ELIG", "VEHICLE");
        future.put("holdType", "FAILED_INSPECTION");
        future.put("startDate", TODAY.plusDays(2).toString());
        placeOk(future);
        Map<String, Object> onFile = hold("VIN-UNUSED", "VIOLATIONS");
        onFile.put("subject", subject("TRAFFIC_FILE", "TF-ELIG"));
        onFile.put("sourceRef", "FINE/ELIG-1");
        placeOk(onFile);

        MvcResult blocked = check("VL_RENEW", List.of(subject("VEHICLE", "VIN-ELIG"),
                subject("TRAFFIC_FILE", "TF-ELIG")), submissionService(), "en");
        assertThat(blocked.getResponse().getStatus()).as(blocked.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode e = body(blocked);
        assertThat(e.get("eligible").asBoolean()).isFalse();
        assertThat(e.get("serviceCode").asString()).isEqualTo("VL_RENEW");
        assertThat(e.get("checkedOn").asString()).isEqualTo(TODAY.toString());
        assertThat(e.get("blocks")).hasSize(2);
        Map<String, JsonNode> byReason = new HashMap<>();
        e.get("blocks").forEach(b -> byReason.put(b.get("reasonCode").asString(), b));
        assertThat(byReason.get("UNPAID_FINES").get("nextStep").asString()).isEqualTo("PAY_FINES");
        assertThat(byReason.get("UNPAID_FINES").get("message").asString())
                .isEqualTo("There are unpaid fines on this record. Pay the outstanding fines, then try again.");
        assertThat(byReason.get("RESTRICTED").get("nextStep").asString()).isEqualTo("CONTACT_AUTHORITY");
        String text = blocked.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).as("no hold details reach the customer")
                .doesNotContain("POLICE", "wanted", "2026/77", "unpaid fines on the vehicle", "VIOLATIONS");

        MvcResult arabic = check("VL_RENEW", List.of(subject("VEHICLE", "VIN-ELIG")), submissionService(), "ar");
        List<String> arabicMessages = new ArrayList<>();
        body(arabic).get("blocks").forEach(b -> arabicMessages.add(b.get("message").asString()));
        assertThat(arabicMessages).contains("يوجد قيد على هذا السجل يمنع تقديم هذه الخدمة. "
                        + "تواصل مع الهيئة أو قم بزيارة أحد مراكز الخدمة للمساعدة.");

        MvcResult otherService = check("DL_ISSUE", List.of(subject("TRAFFIC_FILE", "TF-ELIG")), submissionService(),
                "en");
        assertThat(body(otherService).get("eligible").asBoolean()).isTrue();
        assertThat(body(otherService).get("blocks")).isEmpty();

        assertThat(db.sql("select count(*) from audit_outbox where action = 'ELIGIBILITY_CHECKED'"
                + " and entity_type = 'SubjectEligibility' and entity_id = 'VEHICLE:VIN-ELIG'")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(db.sql("select count(*) from audit_outbox where action = 'ELIGIBILITY_CHECKED'"
                + " and entity_id = 'TRAFFIC_FILE:TF-ELIG' and actor_id = 'u-txn-svc'")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @Tag("CC-032")
    @Tag("CC-001")
    @DisplayName("CC-032/CC-001: a check needs eligibility.check and a valid request")
    void eligibilityCheckRules() throws Exception {
        MvcResult forbidden = check("VL_RENEW", List.of(subject("VEHICLE", "VIN-RULES")), officer(), "en");
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);

        List<Map<String, String>> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(subject("VEHICLE", "VIN-R" + i));
        }
        for (Object[] bad : new Object[][] {
                {"*", List.of(subject("VEHICLE", "VIN-RULES"))},
                {"VL_RENEW", List.of()},
                {"VL_RENEW", six},
                {"VL_RENEW", List.of(subject("VEHICLE", "vin-lower"))}}) {
            @SuppressWarnings("unchecked")
            MvcResult r = check((String) bad[0], (List<Map<String, String>>) bad[1], submissionService(), "en");
            assertThat(r.getResponse().getStatus()).as(String.valueOf(bad[1])).isEqualTo(400);
            assertThat(code(r)).isEqualTo("VALIDATION_FAILED");
        }
        MvcResult twice = check("VL_RENEW", List.of(subject("VEHICLE", "VIN-RULES"), subject("VEHICLE", "VIN-RULES")),
                submissionService(), "en");
        assertThat(twice.getResponse().getStatus()).isEqualTo(422);
        assertThat(code(twice)).isEqualTo("LIST_DUPLICATES");
    }

    // ------------------------------------------------------------------ overrides (CC-034)

    /** A supervisor of the hold's module: HOLD_SUPERVISOR (iam V10). */
    private static RequestPostProcessor supervisor() {
        return user("u-super", "supervisor", "HOLD_SUPERVISOR", "hold.override", "hold.read");
    }

    private static Map<String, Object> override(String transactionReference) {
        return Map.of("transactionReference", transactionReference, "reason", "court ruling allows the renewal",
                "supportingDocument", Map.of("type", "COURT_RULING", "number", "CR/2026/0042"));
    }

    @Test
    @Tag("CC-034")
    @Tag("CC-002")
    @Tag("CC-120")
    @DisplayName("CC-034: a supervisor overrides a hold for one transaction with a reason and document; only that "
            + "transaction's check ignores it; the placer cannot override (CC-002); audited (CC-120)")
    void overrides() throws Exception {
        String id = placeOk(hold("VIN-OVR", "VIOLATIONS"));
        String ref = "VL_RENEW-R1-2026-0000001";
        List<Map<String, String>> vehicle = List.of(subject("VEHICLE", "VIN-OVR"));

        assertThat(body(check("VL_RENEW", vehicle, ref, submissionService(), "en")).get("eligible").asBoolean())
                .as("blocked before the override").isFalse();

        // Who may override: hold.override (403 from the gateway check), never the officer who placed the hold.
        assertThat(postAs("/v1/holds/" + id + "/overrides", override(ref), officer()).getResponse().getStatus())
                .isEqualTo(403);
        MvcResult byPlacer = postAs("/v1/holds/" + id + "/overrides", override(ref),
                user("u-officer", "officer", "HOLD_SUPERVISOR", "hold.override", "hold.read"));
        assertThat(byPlacer.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(byPlacer)).isEqualTo("OVERRIDE_BY_PLACER");

        MvcResult created = call(post("/v1/holds/" + id + "/overrides"), Request.Method.POST,
                "/v1/holds/" + id + "/overrides", override(ref), supervisor(), "BACKOFFICE", "it-override-once");
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/v1/holds/" + id + "/overrides");
        JsonNode o = body(created);
        assertThat(o.get("holdId").asString()).isEqualTo(id);
        assertThat(o.get("transactionReference").asString()).isEqualTo(ref);
        assertThat(o.get("serviceCode").asString()).isEqualTo("VL_RENEW");
        assertThat(o.get("supportingDocument").get("type").asString()).isEqualTo("COURT_RULING");
        assertThat(o.get("supportingDocument").get("number").asString()).isEqualTo("CR/2026/0042");
        assertThat(o.get("overriddenBy").get("id").asString()).isEqualTo("u-super");
        MvcResult replay = call(post("/v1/holds/" + id + "/overrides"), Request.Method.POST,
                "/v1/holds/" + id + "/overrides", override(ref), supervisor(), "BACKOFFICE", "it-override-once");
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(replay).get("id").asString()).isEqualTo(o.get("id").asString());

        MvcResult twice = postAs("/v1/holds/" + id + "/overrides", override(ref), supervisor());
        assertThat(twice.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(twice)).isEqualTo("OVERRIDE_EXISTS");
        MvcResult notBlocked = postAs("/v1/holds/" + id + "/overrides", override("DL_ISSUE-R1-2026-0000001"),
                supervisor());
        assertThat(notBlocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(notBlocked)).isEqualTo("OVERRIDE_NOT_NEEDED");
        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of("transactionReference", "VL_RENEW-1", "reason", "ok reason",
                        "supportingDocument", Map.of("type", "COURT_RULING", "number", "CR-1")),
                Map.<String, Object>of("transactionReference", ref, "reason", "ok reason"),
                Map.<String, Object>of("transactionReference", ref, "supportingDocument",
                        Map.of("type", "COURT_RULING", "number", "CR-1")),
                Map.<String, Object>of("transactionReference", ref, "reason", "ok reason",
                        "supportingDocument", Map.of("type", "court ruling", "number", "CR-1")))) {
            MvcResult r = postAs("/v1/holds/" + id + "/overrides", bad, supervisor());
            assertThat(r.getResponse().getStatus()).as(bad.toString()).isEqualTo(400);
        }

        // Only the overridden transaction is let through; the hold stays Active for everything else.
        MvcResult forRef = check("VL_RENEW", vehicle, ref, submissionService(), "en");
        assertThat(body(forRef).get("eligible").asBoolean()).isTrue();
        assertThat(body(check("VL_RENEW", vehicle, "VL_RENEW-R1-2026-0000002", submissionService(), "en"))
                .get("eligible").asBoolean()).isFalse();
        assertThat(body(check("VL_RENEW", vehicle, submissionService(), "en")).get("eligible").asBoolean()).isFalse();
        assertThat(body(check("VL_TRANSFER", vehicle, "VL_TRANSFER-R1-2026-0000001", submissionService(), "en"))
                .get("eligible").asBoolean()).isFalse();
        MvcResult mismatch = check("VL_RENEW", vehicle, "VL_TRANSFER-R1-2026-0000001", submissionService(), "en");
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(422);
        assertThat(code(mismatch)).isEqualTo("TRANSACTION_SERVICE_MISMATCH");
        assertThat(body(getAs("/v1/holds/" + id, reader())).get("status").asString()).isEqualTo("ACTIVE");

        JsonNode listed = body(getAs("/v1/holds/" + id + "/overrides", supervisor())).get("items");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("reason").asString()).isEqualTo("court ruling allows the renewal");

        List<String> actions = db.sql("select action from audit_outbox where entity_id = :id order by occurred_at")
                .param("id", id).query(String.class).list();
        assertThat(actions).containsExactly("HOLD_PLACED", "HOLD_OVERRIDDEN", "HOLD_VIEWED", "HOLD_OVERRIDES_VIEWED");
        assertThat(db.sql("select reason from audit_outbox where entity_id = :id and action = 'HOLD_OVERRIDDEN'")
                .param("id", id).query(String.class).single()).isEqualTo("court ruling allows the renewal");
        assertThat(db.sql("select count(*) from audit_outbox where action = 'ELIGIBILITY_CHECKED'"
                        + " and entity_id = 'VEHICLE:VIN-OVR' and new_value ->> 'transactionReference' = :ref"
                        + " and new_value -> 'overriddenHolds' @> jsonb_build_array(cast(:id as text))"
                        + " and new_value ->> 'eligible' = 'true'")
                .param("ref", ref).param("id", id).query(Long.class).single()).isEqualTo(1);

        // A lifted hold has nothing left to override.
        postAs("/v1/holds/" + id + "/lifts", Map.of("reason", "fines paid"), officer());
        MvcResult lifted = postAs("/v1/holds/" + id + "/overrides", override("VL_RENEW-R1-2026-0000003"),
                supervisor());
        assertThat(lifted.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(lifted)).isEqualTo("OVERRIDE_HOLD_NOT_ACTIVE");

        // The database keeps overrides as written and repeats the segregation-of-duties rule.
        UUID holdId = UUID.fromString(id);
        assertThatThrownBy(() -> db.sql("update hold_override set reason = 'changed' where hold_id = :id")
                .param("id", holdId).update()).hasMessageContaining("never changed or deleted");
        assertThatThrownBy(() -> db.sql("delete from hold_override where hold_id = :id")
                .param("id", holdId).update()).hasMessageContaining("never changed or deleted");
        assertThatThrownBy(() -> db.sql("""
                        insert into hold_override (id, hold_id, transaction_reference, service_code, reason, document_type,
                            document_number, overridden_at, overridden_by, overridden_channel)
                        values (gen_random_uuid(), :id, 'VL_RENEW-R1-2026-0000009', 'VL_RENEW', 'placer override',
                            'COURT_RULING', 'CR-9', now(), 'u-officer', 'BACKOFFICE')""")
                .param("id", holdId).update()).hasMessageContaining("cannot override");
        assertThatThrownBy(() -> db.sql("""
                        insert into hold_override (id, hold_id, transaction_reference, service_code, reason, document_type,
                            document_number, overridden_at, overridden_by, overridden_channel)
                        values (gen_random_uuid(), :id, 'VL_RENEW-R1-2026-0000009', 'DL_ISSUE', 'wrong service',
                            'COURT_RULING', 'CR-9', now(), 'u-super', 'BACKOFFICE')""")
                .param("id", holdId).update()).hasMessageContaining("ck_hold_override_reference");
    }
}

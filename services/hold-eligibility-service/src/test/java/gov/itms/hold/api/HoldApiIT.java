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
}

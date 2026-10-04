package gov.itms.iam.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
 * End-to-end slice test: HTTP → token → grants resolved from iam's own store (ADR-022) → service → PostgreSQL
 * (Flyway schema, triggers, constraints) → audit outbox. Every request and response is validated against
 * iam-service.v1.yaml (API contract test). Permissions are never put in the test tokens: they come from role
 * assignments, starting from the bootstrap administrator.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.iam.bootstrap-admin.subject=u-admin",
        "itms.iam.bootstrap-admin.username=first.admin"})
@AutoConfigureMockMvc
@Testcontainers
class IamApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static OpenApiInteractionValidator contract;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/iam-service.v1.yaml").build();
    }

    // ------------------------------------------------------------------ actors: identity only, no permission claims

    private static RequestPostProcessor as(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub + ".name"));
    }

    private static final RequestPostProcessor ADMIN = as("u-admin");

    // ------------------------------------------------------------------ helpers with contract validation

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        builder.with(who).header("X-Channel", "BACKOFFICE").header("X-Correlation-Id", "iam-it-correlation")
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

    private MvcResult putAs(String path, Object body, RequestPostProcessor who) throws Exception {
        return call(put(path), Request.Method.PUT, path, body, who);
    }

    private static Map<String, Object> scopes(List<String> regions) {
        return Map.of("regions", regions, "centres", List.of("*"), "modules", List.of("*"));
    }

    private static Map<String, Object> assignment(String subject, String role, List<String> regions) {
        return Map.of("subjectId", subject, "subjectUsername", subject + ".name", "roleCode", role,
                "scopes", scopes(regions), "reason", "integration test");
    }

    private String assign(String subject, String role, List<String> regions, RequestPostProcessor who)
            throws Exception {
        MvcResult r = postAs("/v1/role-assignments", assignment(subject, role, regions), who);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("id").asString();
    }

    private JsonNode myGrants(String subject) throws Exception {
        MvcResult r = getAs("/v1/me/authorisation", as(subject));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return body(r).get("grants");
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: no token → 401; permissions in the token are ignored; grants come only from assignments")
    void authorisationComesFromAssignments() throws Exception {
        assertThat(mvc.perform(get("/v1/roles")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);

        RequestPostProcessor claimsOnly = jwt().jwt(j -> j.subject("u-claims")
                .claim("permissions", List.of("iam.role.read", "iam.assignment.manage")));
        MvcResult refused = getAs("/v1/roles", claimsOnly);
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(refused).get("code").asString()).isEqualTo("FORBIDDEN");

        assertThat(myGrants("u-claims")).isEmpty();
        assertThat(myGrants("u-admin")).singleElement().satisfies(g -> {
            assertThat(g.get("roleCode").asString()).isEqualTo("IT_ADMINISTRATOR");
            assertThat(g.get("scopes").get("regions").get(0).asString()).isEqualTo("*");
        });
    }

    @Test
    @Tag("CC-005")
    @DisplayName("CC-005: create a role, assign it, change its permissions at runtime; the holder's grants follow")
    void rolesChangeWithoutCode() throws Exception {
        JsonNode catalogue = body(getAs("/v1/permissions", ADMIN)).get("items");
        assertThat(catalogue.valueStream().map(n -> n.get("code").asString()))
                .contains("refdata.change.approve", "iam.assignment.manage");
        assertThat(body(getAs("/v1/roles", ADMIN)).get("items").valueStream().map(n -> n.get("code").asString()))
                .contains("IT_ADMINISTRATOR", "REFDATA_MAKER", "REFDATA_CHECKER");

        MvcResult created = postAs("/v1/roles", Map.of("code", "IT_REFDATA_VIEWER",
                "name", Map.of("nl", "مستعرض", "en", "Reference data viewer"),
                "permissions", List.of("refdata.read", "refdata.history.read"), "reason", "new role"), ADMIN);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getHeader("Location")).endsWith("/v1/roles/IT_REFDATA_VIEWER");

        assign("u-viewer", "IT_REFDATA_VIEWER", List.of("R1"), ADMIN);
        assertThat(myGrants("u-viewer").get(0).get("permissions").valueStream().map(JsonNode::asString))
                .containsExactly("refdata.history.read", "refdata.read");

        MvcResult updated = putAs("/v1/roles/IT_REFDATA_VIEWER", Map.of(
                "name", Map.of("nl", "مستعرض", "en", "Reference data viewer"), "active", true,
                "permissions", List.of("refdata.read"), "version", 1, "reason", "history is restricted"), ADMIN);
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(updated).get("version").asLong()).isEqualTo(2);
        assertThat(myGrants("u-viewer").get(0).get("permissions").valueStream().map(JsonNode::asString))
                .containsExactly("refdata.read");

        JsonNode history = body(getAs("/v1/roles/IT_REFDATA_VIEWER/history", ADMIN)).get("items");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("validTo").isNull()).isFalse();
        assertThat(history.get(1).get("changedBy").asString()).isEqualTo("u-admin");

        MvcResult stale = putAs("/v1/roles/IT_REFDATA_VIEWER", Map.of(
                "name", Map.of("nl", "م", "en", "Viewer"), "active", true, "permissions", List.of(),
                "version", 1, "reason", "stale edit"), ADMIN);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(stale).get("code").asString()).isEqualTo("STALE_ROLE");

        MvcResult unknown = postAs("/v1/roles", Map.of("code", "IT_BAD_ROLE", "name", Map.of("nl", "س", "en", "Bad"),
                "permissions", List.of("made.up.permission"), "reason", "typo"), ADMIN);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(unknown).get("code").asString()).isEqualTo("UNKNOWN_PERMISSION");
        // Defence in depth: the database refuses unknown permissions too.
        assertThatThrownBy(() -> db.sql("""
                update role set permissions = array['made.up.permission']::varchar[] where code = 'IT_REFDATA_VIEWER'""")
                .update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    @Tag("CC-002")
    @DisplayName("CC-002: no self-assignment and no changing a role you hold, in the domain and in the database")
    void segregationOfDuties() throws Exception {
        MvcResult self = call(post("/v1/role-assignments").header("Accept-Language", "ar"), Request.Method.POST,
                "/v1/role-assignments", assignment("u-admin", "REFDATA_CHECKER", List.of("*")), ADMIN);
        assertThat(self.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(self).get("code").asString()).isEqualTo("SELF_ASSIGNMENT");
        assertThat(body(self).get("detail").asString()).contains("مسؤول آخر"); // Arabic message (CC-010)
        assertThat(body(self).get("correlationId").asString()).isEqualTo("iam-it-correlation");

        MvcResult ownRole = putAs("/v1/roles/IT_ADMINISTRATOR", Map.of(
                "name", Map.of("nl", "مسؤول", "en", "IT administrator"), "active", true,
                "permissions", List.of("iam.role.read"), "version", 1, "reason", "change my own role"), ADMIN);
        assertThat(ownRole.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(ownRole).get("code").asString()).isEqualTo("ROLE_HELD_BY_ACTOR");

        assertThatThrownBy(() -> db.sql("""
                insert into role_assignment (id, subject_id, role_code, regions, centres, modules, valid_from, status,
                    reason, assigned_by, assigned_at)
                values (gen_random_uuid(), 'u-x', 'REFDATA_MAKER', '{*}', '{*}', '{*}', now(), 'ACTIVE', 'self',
                    'u-x', now())""").update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: a regional administrator manages and sees only assignments inside their region")
    void dataScopesForAdministrators() throws Exception {
        assign("u-r1-admin", "IT_ADMINISTRATOR", List.of("R1"), ADMIN);
        RequestPostProcessor r1Admin = as("u-r1-admin");

        String inR1 = assign("u-r1-maker", "REFDATA_MAKER", List.of("R1"), r1Admin);
        MvcResult beyond = postAs("/v1/role-assignments", assignment("u-r2-maker", "REFDATA_MAKER", List.of("R2")),
                r1Admin);
        assertThat(beyond.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(beyond).get("code").asString()).isEqualTo("SCOPE_EXCEEDED");
        MvcResult national = postAs("/v1/role-assignments", assignment("u-any", "REFDATA_MAKER", List.of("*")),
                r1Admin);
        assertThat(body(national).get("code").asString()).isEqualTo("SCOPE_EXCEEDED");

        String inR2 = assign("u-r2-maker", "REFDATA_MAKER", List.of("R2"), ADMIN);
        JsonNode visible = body(getAs("/v1/role-assignments?roleCode=REFDATA_MAKER", r1Admin)).get("items");
        assertThat(visible.valueStream().map(n -> n.get("id").asString())).contains(inR1).doesNotContain(inR2);
        assertThat(getAs("/v1/role-assignments/" + inR2, r1Admin).getResponse().getStatus()).isEqualTo(404);
        assertThat(getAs("/v1/role-assignments/" + inR2, ADMIN).getResponse().getStatus()).isEqualTo(200);

        assertThat(myGrants("u-r1-maker").get(0).get("scopes").get("regions").get(0).asString()).isEqualTo("R1");
    }

    @Test
    @Tag("CC-120")
    @Tag("DAT-002")
    @DisplayName("CC-120/DAT-002: revocation ends the grants, is audited, and assignments cannot be edited or deleted")
    void revocationAuditAndAppendOnly() throws Exception {
        String id = assign("u-leaver", "REFDATA_MAKER", List.of("R3"), ADMIN);
        assertThat(myGrants("u-leaver")).hasSize(1);

        MvcResult revoked = postAs("/v1/role-assignments/" + id + "/revocations", Map.of("reason", "left the unit"),
                ADMIN);
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(revoked).get("status").asString()).isEqualTo("REVOKED");
        assertThat(body(revoked).get("revokedBy").get("id").asString()).isEqualTo("u-admin");
        assertThat(myGrants("u-leaver")).isEmpty();

        MvcResult again = postAs("/v1/role-assignments/" + id + "/revocations", Map.of("reason", "again"), ADMIN);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(getAs("/v1/role-assignments?subjectId=u-leaver&status=REVOKED", ADMIN).getResponse().getStatus())
                .isEqualTo(200);

        var rows = db.sql("""
                        select action, actor_id, actor_roles::text as roles, channel, correlation_id, reason
                          from audit_outbox where entity_id = :id order by occurred_at""")
                .param("id", id).query().listOfRows();
        assertThat(rows).extracting(r -> r.get("action")).containsExactly("IAM_ROLE_ASSIGNED", "IAM_ROLE_REVOKED");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("actor_id")).isEqualTo("u-admin");
            assertThat(r.get("roles").toString()).contains("IT_ADMINISTRATOR");
            assertThat(r.get("channel")).isEqualTo("BACKOFFICE");
            assertThat(r.get("correlation_id")).isEqualTo("iam-it-correlation");
        });
        assertThat(rows.get(1).get("reason")).isEqualTo("left the unit");

        assertThatThrownBy(() -> db.sql("update role_assignment set regions = '{*}' where id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from role_assignment where id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from role where code = 'REFDATA_MAKER'").update())
                .isInstanceOf(DataAccessException.class);
        // The bootstrap assignment is audited as a system action.
        assertThat(db.sql("select count(*) from audit_outbox where actor_id = 'SYSTEM_BOOTSTRAP'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Tag("SEC-022")
    @Tag("CH-053")
    @DisplayName("SEC-022/CH-053: malformed and rule-breaking requests are refused with plain-language problems")
    void validation() throws Exception {
        Map<String, Object> badPattern = new HashMap<>(assignment("u-v", "REFDATA_MAKER", List.of("r1")));
        MvcResult malformed = postAs("/v1/role-assignments", badPattern, ADMIN);
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(malformed).get("code").asString()).isEqualTo("VALIDATION_FAILED");

        MvcResult mixedAll = postAs("/v1/role-assignments", assignment("u-v", "REFDATA_MAKER", List.of("*", "R1")),
                ADMIN);
        assertThat(mixedAll.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(mixedAll).get("detail").asString())
                .isEqualTo("When regions is * (all), it cannot have other values.");

        Map<String, Object> pastWindow = new HashMap<>(assignment("u-v", "REFDATA_MAKER", List.of("R1")));
        pastWindow.put("validFrom", "2020-01-01T00:00:00Z");
        pastWindow.put("validTo", "2020-02-01T00:00:00Z");
        MvcResult past = postAs("/v1/role-assignments", pastWindow, ADMIN);
        assertThat(body(past).get("code").asString()).isEqualTo("VALIDITY_IN_PAST");

        MvcResult missingRole = getAs("/v1/roles/NO_SUCH_ROLE", ADMIN);
        assertThat(missingRole.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(missingRole).get("detail").asString()).isEqualTo("The role NO_SUCH_ROLE does not exist.");
    }
}

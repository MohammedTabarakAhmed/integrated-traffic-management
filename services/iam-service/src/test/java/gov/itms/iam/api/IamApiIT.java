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
        // Probes are served on the management port only (ADR-037, ObservabilityIT); the API port has none.
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(404);

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
    @Tag("CC-003")
    @DisplayName("CC-003: approval limits are configured per role at runtime, versioned, historised, audited and granted")
    void approvalLimits() throws Exception {
        assertThat(postAs("/v1/roles", Map.of("code", "IT_REFUND_APPROVER",
                "name", Map.of("nl", "معتمد الاسترداد", "en", "Refund approver"),
                "permissions", List.of("refdata.read"), "reason", "limit test role"), ADMIN).getResponse().getStatus())
                .isEqualTo(201);
        assign("u-approver", "IT_REFUND_APPROVER", List.of("R5"), ADMIN);

        // Default: no limits, so no authority to approve.
        JsonNode initial = body(getAs("/v1/roles/IT_REFUND_APPROVER/approval-limits", ADMIN));
        assertThat(initial.get("items")).isEmpty();
        assertThat(initial.get("version").asLong()).isEqualTo(1);
        assertThat(myGrants("u-approver").get(0).get("approvalLimits")).isEmpty();

        MvcResult set = putAs("/v1/roles/IT_REFUND_APPROVER/approval-limits", Map.of("version", 1,
                "reason", "finance policy", "limits", List.of(
                        Map.of("limitType", "REFUND_AMOUNT", "maxAmountMinor", 250000, "currency", "XTS"),
                        Map.of("limitType", "FINE_REDUCTION_AMOUNT", "maxAmountMinor", 5000, "currency", "XTS"))),
                ADMIN);
        assertThat(set.getResponse().getStatus()).as(set.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(set).get("version").asLong()).isEqualTo(2);
        JsonNode granted = myGrants("u-approver").get(0).get("approvalLimits");
        assertThat(granted.valueStream().map(l -> l.get("limitType").asString() + "=" + l.get("maxAmountMinor")
                .asLong())).containsExactly("FINE_REDUCTION_AMOUNT=5000", "REFUND_AMOUNT=250000");

        // A different configured value; FINE_REDUCTION_AMOUNT is removed.
        MvcResult changed = putAs("/v1/roles/IT_REFUND_APPROVER/approval-limits", Map.of("version", 2,
                "reason", "tighten refunds", "limits", List.of(
                        Map.of("limitType", "REFUND_AMOUNT", "maxAmountMinor", 100, "currency", "XTS"))), ADMIN);
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(getAs("/v1/roles/IT_REFUND_APPROVER/approval-limits", ADMIN)).get("items"))
                .singleElement().satisfies(l -> assertThat(l.get("maxAmountMinor").asLong()).isEqualTo(100));

        MvcResult stale = putAs("/v1/roles/IT_REFUND_APPROVER/approval-limits", Map.of("version", 2,
                "reason", "stale", "limits", List.of()), ADMIN);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        MvcResult own = putAs("/v1/roles/IT_ADMINISTRATOR/approval-limits", Map.of("version", 2,
                "reason", "raise my own", "limits", List.of()), ADMIN);
        assertThat(own.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(own).get("code").asString()).isEqualTo("ROLE_HELD_BY_ACTOR");
        MvcResult duplicate = putAs("/v1/roles/IT_REFUND_APPROVER/approval-limits", Map.of("version", 3,
                "reason", "twice", "limits", List.of(
                        Map.of("limitType", "REFUND_AMOUNT", "maxAmountMinor", 1, "currency", "XTS"),
                        Map.of("limitType", "REFUND_AMOUNT", "maxAmountMinor", 2, "currency", "XTS"))), ADMIN);
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(duplicate).get("code").asString()).isEqualTo("LIMIT_DUPLICATE");
        MvcResult noAuthority = putAs("/v1/roles/IT_REFUND_APPROVER/approval-limits", Map.of("version", 3,
                "reason", "not an admin", "limits", List.of()), as("u-approver"));
        assertThat(noAuthority.getResponse().getStatus()).isEqualTo(403);

        var history = db.sql("""
                        select limit_type, max_amount_minor, valid_to is null as open, changed_by, ended_by
                          from approval_limit_history where role_code = 'IT_REFUND_APPROVER' order by history_id""")
                .query().listOfRows();
        assertThat(history).extracting(r -> r.get("limit_type") + "=" + r.get("max_amount_minor") + "/" + r.get("open"))
                .containsExactly("FINE_REDUCTION_AMOUNT=5000/false", "REFUND_AMOUNT=250000/false",
                        "REFUND_AMOUNT=100/true");
        assertThat(history).allSatisfy(r -> assertThat(r.get("changed_by")).isEqualTo("u-admin"));
        assertThat(history.get(0).get("ended_by")).as("removal attributed to the role change").isEqualTo("u-admin");
        assertThat(db.sql("""
                        select count(*) from audit_outbox
                         where action = 'IAM_APPROVAL_LIMITS_REPLACED' and entity_id = 'IT_REFUND_APPROVER'""")
                .query(Long.class).single()).isEqualTo(2);
        assertThatThrownBy(() -> db.sql("""
                update approval_limit set max_amount_minor = -1 where role_code = 'IT_REFUND_APPROVER'""").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from approval_limit_history").update())
                .isInstanceOf(DataAccessException.class);
    }

    private MvcResult delegate(String sourceId, String delegate, String validTo, RequestPostProcessor who)
            throws Exception {
        return postAs("/v1/delegations", Map.of("sourceAssignmentId", sourceId, "delegateSubjectId", delegate,
                "validTo", validTo, "reason", "annual leave"), who);
    }

    private static String inHours(long hours) {
        return java.time.Instant.now().plusSeconds(hours * 3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .toString();
    }

    @Test
    @Tag("CC-004")
    @DisplayName("CC-004: a holder delegates for a set period; the delegate is granted until the source ends; audited")
    void delegationByHolder() throws Exception {
        MvcResult src = postAs("/v1/role-assignments", Map.of("subjectId", "u-holder", "roleCode", "REFDATA_CHECKER",
                "scopes", scopes(List.of("R6")), "validTo", inHours(48), "reason", "checker for R6"), ADMIN);
        String sourceId = body(src).get("id").asString();
        RequestPostProcessor holder = as("u-holder");

        MvcResult created = delegate(sourceId, "u-deputy", inHours(24), holder);
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode d = body(created);
        String delegationId = d.get("id").asString();
        assertThat(d.get("source").asString()).isEqualTo("DELEGATION");
        assertThat(d.get("delegatedFrom").asString()).isEqualTo(sourceId);
        assertThat(d.get("assignedBy").get("id").asString()).isEqualTo("u-holder");
        assertThat(myGrants("u-deputy")).singleElement().satisfies(g -> {
            assertThat(g.get("roleCode").asString()).isEqualTo("REFDATA_CHECKER");
            assertThat(g.get("scopes").get("regions").get(0).asString()).isEqualTo("R6");
        });

        assertThat(body(getAs("/v1/delegations", holder)).get("items")).singleElement()
                .satisfies(n -> assertThat(n.get("id").asString()).isEqualTo(delegationId));
        assertThat(body(getAs("/v1/delegations?subjectId=u-holder", as("u-stranger"))).get("items")).isEmpty();
        assertThat(body(getAs("/v1/delegations?subjectId=u-deputy", ADMIN)).get("items")).hasSize(1);

        MvcResult tooLong = delegate(sourceId, "u-other", inHours(72), holder);
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(tooLong).get("code").asString()).isEqualTo("DELEGATION_EXCEEDS_SOURCE");
        MvcResult chain = delegate(delegationId, "u-other", inHours(1), as("u-deputy"));
        assertThat(body(chain).get("code").asString()).isEqualTo("REDELEGATION");
        MvcResult self = delegate(sourceId, "u-holder", inHours(1), holder);
        assertThat(self.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(self).get("code").asString()).isEqualTo("SELF_DELEGATION");
        MvcResult stranger = delegate(sourceId, "u-other", inHours(1), as("u-stranger"));
        assertThat(stranger.getResponse().getStatus()).isEqualTo(404);
        Map<String, Object> noEnd = Map.of("sourceAssignmentId", sourceId, "delegateSubjectId", "u-other",
                "reason", "open ended");
        assertThat(postAs("/v1/delegations", noEnd, holder).getResponse().getStatus()).isEqualTo(400);

        // Revoking the source ends the delegate's grant even though the delegation itself is still ACTIVE.
        postAs("/v1/role-assignments/" + sourceId + "/revocations", Map.of("reason", "holder moved"), ADMIN);
        assertThat(myGrants("u-deputy")).isEmpty();

        MvcResult revoked = postAs("/v1/delegations/" + delegationId + "/revocations", Map.of("reason", "tidy up"),
                holder);
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(revoked).get("status").asString()).isEqualTo("REVOKED");
        assertThat(postAs("/v1/delegations/" + sourceId + "/revocations", Map.of("reason", "not a delegation"),
                ADMIN).getResponse().getStatus()).isEqualTo(404);

        assertThat(db.sql("select action from audit_outbox where entity_id = :id order by occurred_at")
                .param("id", delegationId).query(String.class).list())
                .containsExactly("IAM_DELEGATION_CREATED", "IAM_DELEGATION_REVOKED");
        assertThat(db.sql("select actor_id from audit_outbox where entity_id = :id and action = 'IAM_DELEGATION_CREATED'")
                .param("id", delegationId).query(String.class).single()).isEqualTo("u-holder");
    }

    @Test
    @Tag("CC-004")
    @DisplayName("CC-004: an administrator delegates for an absent holder within scope; the database guards the rules")
    void delegationByAdministratorAndDatabaseGuards() throws Exception {
        String sourceId = assign("u-absent", "REFDATA_MAKER", List.of("R7"), ADMIN);
        assign("u-r8-admin", "IT_ADMINISTRATOR", List.of("R8"), ADMIN);

        MvcResult outOfScope = delegate(sourceId, "u-cover", inHours(8), as("u-r8-admin"));
        assertThat(outOfScope.getResponse().getStatus()).isEqualTo(404);

        MvcResult created = delegate(sourceId, "u-cover", inHours(8), ADMIN);
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        String delegationId = body(created).get("id").asString();
        assertThat(myGrants("u-cover")).hasSize(1);
        assertThat(postAs("/v1/delegations/" + delegationId + "/revocations", Map.of("reason", "nosy"),
                as("u-cover")).getResponse().getStatus()).isEqualTo(403);

        // Defence in depth: the trigger and constraints refuse invalid delegations written directly.
        String insert = """
                insert into role_assignment (id, subject_id, role_code, regions, centres, modules, valid_from,
                    valid_to, status, reason, assigned_by, assigned_at, source, delegated_from)
                values (gen_random_uuid(), :subject, :role, '{R7}', '{*}', '{*}', now(), :to, 'ACTIVE', 'direct',
                    'u-admin', now(), 'DELEGATION', cast(:from as uuid))""";
        assertThatThrownBy(() -> db.sql(insert).param("subject", "u-x").param("role", "REFDATA_MAKER")
                .param("to", java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(60)))
                .param("from", delegationId).update()).as("re-delegation").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be delegated again");
        assertThatThrownBy(() -> db.sql(insert).param("subject", "u-x").param("role", "REFDATA_CHECKER")
                .param("to", java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(60)))
                .param("from", sourceId).update()).as("other role").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("role and scopes of its source");
        assertThatThrownBy(() -> db.sql(insert).param("subject", "u-absent").param("role", "REFDATA_MAKER")
                .param("to", java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(60)))
                .param("from", sourceId).update()).as("to the holder").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("holder of its source");
        assertThatThrownBy(() -> db.sql(insert).param("subject", "u-x").param("role", "REFDATA_MAKER")
                .param("to", null).param("from", sourceId).update()).as("no end")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_delegation_validity");
        assertThatThrownBy(() -> db.sql("update role_assignment set source = 'DIRECT', delegated_from = null "
                + "where id = cast(:id as uuid)").param("id", delegationId).update()).as("immutable")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("can only be revoked once");
    }

    @Test
    @Tag("CC-004")
    @DisplayName("CC-004: a holder without any IAM permission lists their own assignments and delegates one by its id")
    void myAssignmentsEnableSelfServiceDelegation() throws Exception {
        String own = assign("u-self", "REFDATA_MAKER", List.of("R10"), ADMIN);
        RequestPostProcessor self = as("u-self");
        assertThat(getAs("/v1/role-assignments?subjectId=u-self", self).getResponse().getStatus()).isEqualTo(403);

        JsonNode mine = body(getAs("/v1/me/assignments", self)).get("items");
        assertThat(mine).singleElement().satisfies(a -> {
            assertThat(a.get("id").asString()).isEqualTo(own);
            assertThat(a.get("source").asString()).isEqualTo("DIRECT");
        });
        assertThat(body(getAs("/v1/me/assignments?status=REVOKED", self)).get("items")).isEmpty();
        assertThat(body(getAs("/v1/me/assignments", as("u-nobody"))).get("items")).isEmpty();

        String delegationId = body(delegate(mine.get(0).get("id").asString(), "u-self-cover", inHours(2), self))
                .get("id").asString();
        assertThat(body(getAs("/v1/me/assignments", as("u-self-cover"))).get("items")).singleElement()
                .satisfies(a -> assertThat(a.get("delegatedFrom").asString()).isEqualTo(own));
        postAs("/v1/delegations/" + delegationId + "/revocations", Map.of("reason", "back"), self);
        assertThat(body(getAs("/v1/me/assignments?status=REVOKED", as("u-self-cover"))).get("items")).hasSize(1);
    }

    private void createRole(String code) throws Exception {
        MvcResult r = postAs("/v1/roles", Map.of("code", code, "name", Map.of("nl", "دور", "en", code),
                "permissions", List.of("refdata.read"), "reason", "conflict test role"), ADMIN);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
    }

    @Test
    @Tag("CC-002")
    @DisplayName("CC-002: configured conflicting roles are refused on assignment and delegation; violations; audited")
    void conflictingRoles() throws Exception {
        createRole("IT_CASHIER");
        createRole("IT_RECONCILER");
        // No pair configured (the default, Q-030): one person may hold both.
        String dualCashier = assign("u-dual", "IT_CASHIER", List.of("R12"), ADMIN);
        String dualReconciler = assign("u-dual", "IT_RECONCILER", List.of("R12"), ADMIN);

        MvcResult created = postAs("/v1/role-conflicts", Map.of("roleA", "IT_RECONCILER", "roleB", "IT_CASHIER",
                "reason", "cash handling and reconciliation are separated"), ADMIN);
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode pair = body(created);
        String pairId = pair.get("id").asString();
        assertThat(pair.get("roleA").asString()).isEqualTo("IT_CASHIER");
        assertThat(pair.get("roleB").asString()).isEqualTo("IT_RECONCILER");
        assertThat(created.getResponse().getHeader("Location")).endsWith("/v1/role-conflicts/" + pairId);

        // Existing holders keep their roles and are listed as violations.
        assertThat(myGrants("u-dual")).hasSize(2);
        JsonNode violations = body(getAs("/v1/role-conflicts/violations", ADMIN)).get("items");
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.get("conflictId").asString()).isEqualTo(pairId);
            assertThat(v.get("subjectId").asString()).isEqualTo("u-dual");
            assertThat(v.get("assignmentIds").valueStream().map(JsonNode::asString))
                    .containsExactlyInAnyOrder(dualCashier, dualReconciler);
        });

        assign("u-cashier", "IT_CASHIER", List.of("R12"), ADMIN);
        MvcResult refused = postAs("/v1/role-assignments", assignment("u-cashier", "IT_RECONCILER", List.of("R13")),
                ADMIN);
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(refused).get("code").asString()).isEqualTo("ROLE_CONFLICT");
        assertThat(body(refused).get("detail").asString()).isEqualTo("The role IT_RECONCILER conflicts with the role "
                + "IT_CASHIER, which this person already holds. One person cannot hold both.");
        assertThat(body(refused).get("requirementRefs").get(0).asString()).isEqualTo("CC-002");

        String reconcilerSource = assign("u-reconciler", "IT_RECONCILER", List.of("R12"), ADMIN);
        MvcResult delegation = delegate(reconcilerSource, "u-cashier", inHours(4), as("u-reconciler"));
        assertThat(delegation.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(delegation).get("code").asString()).isEqualTo("ROLE_CONFLICT");

        MvcResult twice = postAs("/v1/role-conflicts", Map.of("roleA", "IT_CASHIER", "roleB", "IT_RECONCILER",
                "reason", "again"), ADMIN);
        assertThat(twice.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(twice).get("code").asString()).isEqualTo("ROLE_CONFLICT_EXISTS");
        MvcResult same = postAs("/v1/role-conflicts", Map.of("roleA", "IT_CASHIER", "roleB", "IT_CASHIER",
                "reason", "same"), ADMIN);
        assertThat(body(same).get("code").asString()).isEqualTo("ROLE_CONFLICT_SAME_ROLE");
        assertThat(postAs("/v1/role-conflicts", Map.of("roleA", "IT_CASHIER", "roleB", "NO_SUCH_ROLE",
                "reason", "typo"), ADMIN).getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs("/v1/role-conflicts", Map.of("roleA", "IT_CASHIER", "roleB", "IT_RECONCILER",
                "reason", "not an admin"), as("u-cashier")).getResponse().getStatus()).isEqualTo(403);

        // Defence in depth while the pair is ACTIVE.
        assertThatThrownBy(() -> db.sql("""
                insert into role_assignment (id, subject_id, role_code, regions, centres, modules, valid_from, status,
                    reason, assigned_by, assigned_at)
                values (gen_random_uuid(), 'u-cashier', 'IT_RECONCILER', '{R14}', '{*}', '{*}', now(), 'ACTIVE',
                    'direct', 'u-admin', now())""").update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("conflicts with role IT_RECONCILER");
        assertThatThrownBy(() -> db.sql("""
                insert into role_conflict (id, role_a, role_b, reason, status, created_by, created_at)
                values (gen_random_uuid(), 'IT_CASHIER', 'IT_RECONCILER', 'direct', 'ACTIVE', 'u-admin', now())""")
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("uq_role_conflict_active");
        assertThatThrownBy(() -> db.sql("""
                insert into role_conflict (id, role_a, role_b, reason, status, created_by, created_at)
                values (gen_random_uuid(), 'IT_RECONCILER', 'IT_CASHIER', 'direct', 'ACTIVE', 'u-admin', now())""")
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_role_conflict_order");
        assertThatThrownBy(() -> db.sql("update role_conflict set reason = 'edited' where id = cast(:id as uuid)")
                .param("id", pairId).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("can only be retired once");
        assertThatThrownBy(() -> db.sql("delete from role_conflict where id = cast(:id as uuid)")
                .param("id", pairId).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be deleted");

        MvcResult retired = postAs("/v1/role-conflicts/" + pairId + "/retirements",
                Map.of("reason", "policy withdrawn"), ADMIN);
        assertThat(retired.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(retired).get("status").asString()).isEqualTo("RETIRED");
        assertThat(body(retired).get("retiredBy").get("id").asString()).isEqualTo("u-admin");
        assertThat(postAs("/v1/role-conflicts/" + pairId + "/retirements", Map.of("reason", "again"), ADMIN)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(body(getAs("/v1/role-conflicts/violations", ADMIN)).get("items")).isEmpty();
        assertThat(body(getAs("/v1/role-conflicts?status=RETIRED", ADMIN)).get("items").valueStream()
                .map(n -> n.get("id").asString())).contains(pairId);
        assertThat(postAs("/v1/role-assignments", assignment("u-cashier", "IT_RECONCILER", List.of("R13")), ADMIN)
                .getResponse().getStatus()).as("no longer enforced").isEqualTo(201);

        assertThat(db.sql("select action from audit_outbox where entity_id = :id order by occurred_at")
                .param("id", pairId).query(String.class).list())
                .containsExactly("IAM_ROLE_CONFLICT_CREATED", "IAM_ROLE_CONFLICT_RETIRED");
    }

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: the directory feed deactivates a leaver: all roles and delegations end at once; idempotent")
    void leaverDeactivationByDirectoryFeed() throws Exception {
        assertThat(body(getAs("/v1/roles/DIRECTORY_FEED", ADMIN)).get("permissions").valueStream()
                .map(JsonNode::asString)).containsExactlyInAnyOrder("iam.subject.deactivate", "iam.assignment.read");
        assertThat(body(getAs("/v1/roles/IT_ADMINISTRATOR", ADMIN)).get("permissions").valueStream()
                .map(JsonNode::asString)).contains("iam.subject.deactivate");

        String maker = assign("u-gone", "REFDATA_MAKER", List.of("R11"), ADMIN);
        assign("u-gone", "REFDATA_CHECKER", List.of("R15"), ADMIN);
        String made = body(delegate(maker, "u-gone-cover", inHours(3), as("u-gone"))).get("id").asString();
        assign("svc-feed", "DIRECTORY_FEED", List.of("*"), ADMIN);
        assign("svc-feed-r11", "DIRECTORY_FEED", List.of("R11"), ADMIN);
        RequestPostProcessor feed = as("svc-feed");

        MvcResult partial = postAs("/v1/subjects/u-gone/deactivations", Map.of("reason", "left the Authority"),
                as("svc-feed-r11"));
        assertThat(partial.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(partial).get("code").asString()).isEqualTo("SCOPE_EXCEEDED");
        assertThat(myGrants("u-gone")).as("nothing revoked").hasSize(2);
        assertThat(postAs("/v1/subjects/u-gone/deactivations", Map.of("reason", "not mine"), as("u-gone-cover"))
                .getResponse().getStatus()).isEqualTo(403);

        MvcResult done = postAs("/v1/subjects/u-gone/deactivations", Map.of("reason", "left the Authority"), feed);
        assertThat(done.getResponse().getStatus()).as(done.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(done).get("revokedCount").asInt()).isEqualTo(3);
        assertThat(body(done).get("revoked").valueStream().map(n -> n.get("status").asString())).containsOnly("REVOKED");
        assertThat(body(done).get("revoked").valueStream().map(n -> n.get("id").asString())).contains(maker, made);
        assertThat(myGrants("u-gone")).isEmpty();
        assertThat(myGrants("u-gone-cover")).isEmpty();
        assertThat(body(getAs("/v1/role-assignments?subjectId=u-gone&status=ACTIVE", feed)).get("items")).isEmpty();

        MvcResult repeat = postAs("/v1/subjects/u-gone/deactivations", Map.of("reason", "feed resent"), feed);
        assertThat(repeat.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(repeat).get("revokedCount").asInt()).isZero();
        assertThat(postAs("/v1/subjects/bad!id/deactivations", Map.of("reason", "x y z"), feed)
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(getAs("/v1/roles", feed).getResponse().getStatus()).as("the feed can do nothing else")
                .isEqualTo(403);

        var summaries = db.sql("""
                        select actor_id, reason from audit_outbox
                         where action = 'IAM_SUBJECT_DEACTIVATED' and entity_id = 'u-gone' order by occurred_at""")
                .query().listOfRows();
        assertThat(summaries).extracting(r -> r.get("actor_id")).containsExactly("svc-feed", "svc-feed");
        assertThat(summaries).extracting(r -> r.get("reason")).containsExactly("left the Authority", "feed resent");
        assertThat(db.sql("select action from audit_outbox where entity_id = :id order by occurred_at")
                .param("id", made).query(String.class).list())
                .containsExactly("IAM_DELEGATION_CREATED", "IAM_DELEGATION_REVOKED");
        assertThat(db.sql("select actor_id from audit_outbox where entity_id = :id and action = 'IAM_ROLE_REVOKED'")
                .param("id", maker).query(String.class).single()).isEqualTo("svc-feed");
    }

    // ------------------------------------------------------------------ access reviews (SEC-007 manager half)

    private MvcResult openReview(List<String> regions, String dueAt, RequestPostProcessor who) throws Exception {
        return postAs("/v1/access-reviews", Map.of("title", "Quarterly access review", "scopes", scopes(regions),
                "dueAt", dueAt, "reason", "quarterly review (SEC-007)"), who);
    }

    private String openReview(List<String> regions) throws Exception {
        MvcResult r = openReview(regions, inHours(48), ADMIN);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("id").asString();
    }

    private JsonNode reviewItems(String query, RequestPostProcessor who) throws Exception {
        MvcResult r = getAs("/v1/access-review-items?" + query, who);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).get("items");
    }

    private String itemOf(String campaignId, String subject, RequestPostProcessor who) throws Exception {
        return reviewItems("campaignId=" + campaignId + "&subjectId=" + subject, who).get(0).get("id").asString();
    }

    private MvcResult decideItem(String itemId, String decision, String reason, RequestPostProcessor who)
            throws Exception {
        Map<String, Object> request = reason == null ? Map.of("decision", decision)
                : Map.of("decision", decision, "reason", reason);
        return postAs("/v1/access-review-items/" + itemId + "/decisions", request, who);
    }

    private MvcResult closeReview(String campaignId, RequestPostProcessor who) throws Exception {
        return postAs("/v1/access-reviews/" + campaignId + "/closures", Map.of("reason", "quarter done"), who);
    }

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: admin opens a review, a manager keeps one and revokes one, admin closes; access follows")
    void accessReviewCampaignEndToEnd() throws Exception {
        assertThat(body(getAs("/v1/roles/ACCESS_REVIEWER", ADMIN)).get("permissions").valueStream()
                .map(JsonNode::asString)).containsExactlyInAnyOrder("iam.review.read", "iam.review.decide");
        assertThat(body(getAs("/v1/roles/IT_ADMINISTRATOR", ADMIN)).get("permissions").valueStream()
                .map(JsonNode::asString)).contains("iam.review.manage", "iam.review.read")
                .doesNotContain("iam.review.decide");
        assign("u-ar-rev", "ACCESS_REVIEWER", List.of("AR1", "AR9"), ADMIN);
        RequestPostProcessor reviewer = as("u-ar-rev");
        assign("u-ar-keep", "REFDATA_MAKER", List.of("AR1"), ADMIN);
        String revokedAssignment = assign("u-ar-revoke", "REFDATA_MAKER", List.of("AR1"), ADMIN);
        assign("u-ar-idle", "REFDATA_MAKER", List.of("AR1"), ADMIN);

        MvcResult opened = openReview(List.of("AR1"), inHours(48), ADMIN);
        assertThat(opened.getResponse().getStatus()).as(opened.getResponse().getContentAsString()).isEqualTo(201);
        String id = body(opened).get("id").asString();
        assertThat(opened.getResponse().getHeader("Location")).endsWith("/v1/access-reviews/" + id);
        assertThat(body(opened).get("status").asString()).isEqualTo("OPEN");
        assertThat(body(opened).get("counts").get("total").asInt()).isEqualTo(3);
        assertThat(body(opened).get("skippedUnderReview").asInt()).isZero();

        JsonNode queue = reviewItems("campaignId=" + id, reviewer);
        assertThat(queue).hasSize(3).allSatisfy(i -> {
            assertThat(i.get("status").asString()).isEqualTo("PENDING");
            assertThat(i.get("assignedBy").get("id").asString()).isEqualTo("u-admin");
            assertThat(i.get("overdue").asBoolean()).isFalse();
        });

        MvcResult kept = decideItem(itemOf(id, "u-ar-keep", reviewer), "KEEP", null, reviewer);
        assertThat(kept.getResponse().getStatus()).as(kept.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(kept).get("status").asString()).isEqualTo("KEPT");
        assertThat(body(kept).get("decidedBy").get("id").asString()).isEqualTo("u-ar-rev");
        assertThat(body(kept).get("decidedLate").asBoolean()).isFalse();
        assertThat(myGrants("u-ar-revoke")).hasSize(1);
        MvcResult revoked = decideItem(itemOf(id, "u-ar-revoke", reviewer), "REVOKE", "left the unit", reviewer);
        assertThat(revoked.getResponse().getStatus()).as(revoked.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(revoked).get("status").asString()).isEqualTo("REVOKED");
        assertThat(myGrants("u-ar-revoke")).as("the revoked subject loses the grant at once").isEmpty();
        JsonNode assignmentAfter = body(getAs("/v1/role-assignments/" + revokedAssignment, ADMIN));
        assertThat(assignmentAfter.get("status").asString()).isEqualTo("REVOKED");
        assertThat(assignmentAfter.get("revokedBy").get("id").asString()).isEqualTo("u-ar-rev");
        assertThat(assignmentAfter.get("revocationReason").asString()).isEqualTo("left the unit");

        JsonNode counts = body(getAs("/v1/access-reviews/" + id, ADMIN)).get("counts");
        assertThat(counts.get("pending").asInt()).isEqualTo(1);
        assertThat(counts.get("kept").asInt()).isEqualTo(1);
        assertThat(counts.get("revoked").asInt()).isEqualTo(1);

        MvcResult closed = closeReview(id, ADMIN);
        assertThat(closed.getResponse().getStatus()).as(closed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(closed).get("status").asString()).isEqualTo("CLOSED");
        assertThat(body(closed).get("closedBy").get("id").asString()).isEqualTo("u-admin");
        assertThat(body(closed).get("counts").get("notReviewed").asInt()).isEqualTo(1);
        assertThat(body(closed).get("counts").get("pending").asInt()).isZero();
        assertThat(myGrants("u-ar-idle")).as("closing revokes nothing").hasSize(1);
        assertThat(body(getAs("/v1/access-reviews?status=CLOSED", ADMIN)).get("items").valueStream()
                .map(n -> n.get("id").asString())).contains(id);
        assertThat(reviewItems("campaignId=" + id + "&status=NOT_REVIEWED", reviewer)).singleElement()
                .satisfies(i -> assertThat(i.get("subjectId").asString()).isEqualTo("u-ar-idle"));

        assertThat(db.sql("select action from audit_outbox where entity_id = :id order by occurred_at")
                .param("id", id).query(String.class).list())
                .containsExactly("IAM_ACCESS_REVIEW_OPENED", "IAM_ACCESS_REVIEW_CLOSED");
        assertThat(db.sql("select action from audit_outbox where entity_id = :id order by occurred_at")
                .param("id", revokedAssignment).query(String.class).list())
                .containsExactly("IAM_ROLE_ASSIGNED", "IAM_ROLE_REVOKED");
    }

    @Test
    @Tag("SEC-007")
    @Tag("CC-001")
    @DisplayName("SEC-007/CC-001: managers decide but cannot open or close; admins cannot decide; scopes apply")
    void accessReviewAuthorisationAndScope() throws Exception {
        assign("u-az-rev", "ACCESS_REVIEWER", List.of("AZ1", "AZ9"), ADMIN);
        RequestPostProcessor reviewer = as("u-az-rev");
        assign("u-az-1", "REFDATA_MAKER", List.of("AZ1"), ADMIN);
        assign("u-az-2", "REFDATA_MAKER", List.of("AZ2"), ADMIN);
        assign("u-az-admin", "IT_ADMINISTRATOR", List.of("AZ1", "AZ7"), ADMIN); // not inside the review below

        assertThat(mvc.perform(get("/v1/access-reviews")).andReturn().getResponse().getStatus()).isEqualTo(401);
        MvcResult reviewerOpens = openReview(List.of("AZ1"), inHours(48), reviewer);
        assertThat(reviewerOpens.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(reviewerOpens).get("code").asString()).isEqualTo("FORBIDDEN");
        MvcResult beyond = openReview(List.of("AZ2"), inHours(48), as("u-az-admin"));
        assertThat(beyond.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(beyond).get("code").asString()).isEqualTo("SCOPE_EXCEEDED");
        assertThat(getAs("/v1/access-reviews", as("u-az-1")).getResponse().getStatus()).isEqualTo(403);

        String id = openReview(List.of("AZ1", "AZ2"));
        assertThat(getAs("/v1/access-reviews/" + id, reviewer).getResponse().getStatus())
                .as("the header of a review wider than the reviewer's scope").isEqualTo(404);
        assertThat(body(getAs("/v1/access-reviews", reviewer)).get("items").valueStream()
                .map(n -> n.get("id").asString())).doesNotContain(id);
        JsonNode visible = reviewItems("campaignId=" + id, reviewer);
        assertThat(visible).singleElement()
                .satisfies(i -> assertThat(i.get("subjectId").asString()).isEqualTo("u-az-1"));
        String outside = itemOf(id, "u-az-2", ADMIN);
        assertThat(decideItem(outside, "KEEP", null, reviewer).getResponse().getStatus()).isEqualTo(404);
        MvcResult adminDecides = decideItem(visible.get(0).get("id").asString(), "KEEP", null, ADMIN);
        assertThat(adminDecides.getResponse().getStatus()).as("administrators do not certify access").isEqualTo(403);
        assertThat(body(adminDecides).get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(closeReview(id, reviewer).getResponse().getStatus()).isEqualTo(403);
        assertThat(closeReview(id, as("u-az-admin")).getResponse().getStatus())
                .as("outside the regional admin's scope").isEqualTo(404);

        assertThat(decideItem(visible.get(0).get("id").asString(), "KEEP", null, reviewer).getResponse()
                .getStatus()).isEqualTo(200);
        assertThat(reviewItems("campaignId=" + java.util.UUID.randomUUID(), reviewer)).isEmpty();
        assertThat(getAs("/v1/access-review-items?status=MAYBE", reviewer).getResponse().getStatus()).isEqualTo(400);
        assertThat(getAs("/v1/access-reviews/not-a-uuid", ADMIN).getResponse().getStatus()).isEqualTo(400);
        assertThat(getAs("/v1/access-reviews/" + java.util.UUID.randomUUID(), ADMIN).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @Tag("SEC-007")
    @Tag("CC-002")
    @DisplayName("SEC-007/CC-002: nobody reviews their own access or an assignment they granted")
    void accessReviewSegregationOfDuties() throws Exception {
        assign("u-sd-rev", "ACCESS_REVIEWER", List.of("SD1", "SD9"), ADMIN);
        assign("u-sd-rev", "REFDATA_MAKER", List.of("SD1"), ADMIN);
        assign("u-sd-boss", "IT_ADMINISTRATOR", List.of("SD1", "SD8"), ADMIN);
        assign("u-sd-boss", "ACCESS_REVIEWER", List.of("SD1", "SD9"), ADMIN);
        assign("u-sd-x", "REFDATA_MAKER", List.of("SD1"), as("u-sd-boss"));
        RequestPostProcessor reviewer = as("u-sd-rev");
        RequestPostProcessor boss = as("u-sd-boss");
        String id = openReview(List.of("SD1"));

        MvcResult self = decideItem(itemOf(id, "u-sd-rev", reviewer), "KEEP", null, reviewer);
        assertThat(self.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(self).get("code").asString()).isEqualTo("ACCESS_REVIEW_SELF");
        assertThat(body(self).get("requirementRefs").get(0).asString()).isEqualTo("CC-002");
        MvcResult ownGrant = decideItem(itemOf(id, "u-sd-x", boss), "REVOKE", "granted it myself", boss);
        assertThat(ownGrant.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(ownGrant).get("code").asString()).isEqualTo("ACCESS_REVIEW_OWN_GRANT");
        assertThat(myGrants("u-sd-x")).as("nothing revoked").hasSize(1);

        assertThat(decideItem(itemOf(id, "u-sd-x", reviewer), "KEEP", null, reviewer).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(decideItem(itemOf(id, "u-sd-rev", boss), "KEEP", "still needed", boss).getResponse()
                .getStatus()).isEqualTo(200);
    }

    @Test
    @Tag("SEC-007")
    @Tag("DAT-002")
    @DisplayName("SEC-007/DAT-002: the database guards reviews: append-only, one transition, SoD, open, one pending")
    void accessReviewDatabaseGuards() throws Exception {
        assign("u-db-1", "REFDATA_MAKER", List.of("DB1"), ADMIN);
        String second = assign("u-db-2", "REFDATA_MAKER", List.of("DB1"), ADMIN);
        assign("u-db-rev", "ACCESS_REVIEWER", List.of("DB1", "DB9"), ADMIN);
        String id = openReview(List.of("DB1"));
        String item1 = itemOf(id, "u-db-1", ADMIN);
        String item2 = itemOf(id, "u-db-2", ADMIN);

        assertThatThrownBy(() -> db.sql("delete from access_review_item where id = cast(:id as uuid)")
                .param("id", item1).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> db.sql("delete from access_review_campaign where id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> db.sql("update access_review_item set role_code = 'IT_ADMINISTRATOR' "
                + "where id = cast(:id as uuid)").param("id", item1).update()).as("snapshot edit")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("can only be decided or closed once");
        assertThatThrownBy(() -> db.sql("update access_review_campaign set title = 'Edited title' "
                + "where id = cast(:id as uuid)").param("id", id).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("can only be closed once");
        assertThatThrownBy(() -> db.sql("""
                update access_review_item set status = 'KEPT', decided_by = subject_id, decided_at = now()
                 where id = cast(:id as uuid)""").param("id", item1).update()).as("self-review")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_item_no_self_review");
        assertThatThrownBy(() -> db.sql("""
                update access_review_item set status = 'KEPT', decided_by = 'u-admin', decided_at = now()
                 where id = cast(:id as uuid)""").param("id", item1).update()).as("assigner reviews")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_item_not_assigner");
        assertThatThrownBy(() -> db.sql("""
                update access_review_item set status = 'REVOKED', decided_by = 'u-db-rev', decided_at = now(),
                       decision_reason = 'direct'
                 where id = cast(:id as uuid)""").param("id", item1).update()).as("assignment still active")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("revoked first");
        assertThatThrownBy(() -> db.sql("""
                update access_review_item set status = 'REVOKED', decided_by = 'u-db-rev', decided_at = now()
                 where id = cast(:id as uuid)""").param("id", item1).update()).as("revoke without reason")
                .isInstanceOf(DataAccessException.class);

        assertThat(decideItem(item1, "KEEP", null, as("u-db-rev")).getResponse().getStatus()).isEqualTo(200);
        assertThatThrownBy(() -> db.sql("update access_review_item set status = 'NOT_REVIEWED' "
                + "where id = cast(:id as uuid)").param("id", item1).update()).as("second transition")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("can only be decided or closed once");

        String other = openReview(List.of("DB1"));
        String duplicatePending = """
                insert into access_review_item (id, campaign_id, assignment_id, subject_id, subject_username,
                    role_code, regions, centres, modules, assigned_by, valid_from, valid_to, status)
                select gen_random_uuid(), cast(:campaign as uuid), a.id, a.subject_id, a.subject_username,
                       a.role_code, a.regions, a.centres, a.modules, a.assigned_by, a.valid_from, a.valid_to,
                       'PENDING'
                  from role_assignment a where a.id = cast(:assignment as uuid)""";
        assertThatThrownBy(() -> db.sql(duplicatePending).param("campaign", other).param("assignment", second)
                .update()).as("one pending item per assignment").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_item_pending");

        assertThat(closeReview(id, ADMIN).getResponse().getStatus()).isEqualTo(200);
        assertThat(closeReview(other, ADMIN).getResponse().getStatus()).isEqualTo(200);
        assertThatThrownBy(() -> db.sql("""
                update access_review_item set status = 'KEPT', decided_by = 'u-db-rev', decided_at = now()
                 where id = cast(:id as uuid)""").param("id", item2).update()).as("decision on a closed review")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("only be decided while the review is open");
        assertThatThrownBy(() -> db.sql(duplicatePending).param("campaign", other).param("assignment", second)
                .update()).as("items only in an open review").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("only be added to an open review");
        assertThatThrownBy(() -> db.sql("""
                insert into access_review_campaign (id, title, regions, centres, modules, due_at, reason, status,
                    created_by, created_at)
                values (gen_random_uuid(), 'Past review', '{DB1}', '{*}', '{*}', now() - interval '1 day', 'direct',
                    'OPEN', 'u-admin', now())""").update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_campaign_due");
    }

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: repeated decisions and closures are refused; concurrent opens never review twice")
    void accessReviewIdempotencyAndConflicts() throws Exception {
        assign("u-id-1", "REFDATA_MAKER", List.of("ID1"), ADMIN);
        assign("u-id-2", "REFDATA_MAKER", List.of("ID1"), ADMIN);
        assign("u-id-rev", "ACCESS_REVIEWER", List.of("ID1", "ID3", "ID9"), ADMIN);
        RequestPostProcessor reviewer = as("u-id-rev");
        String first = openReview(List.of("ID1"));
        String item1 = itemOf(first, "u-id-1", reviewer);
        String item2 = itemOf(first, "u-id-2", reviewer);

        assertThat(decideItem(item1, "KEEP", null, reviewer).getResponse().getStatus()).isEqualTo(200);
        MvcResult again = decideItem(item1, "REVOKE", "changed my mind", reviewer);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(again).get("code").asString()).isEqualTo("ACCESS_REVIEW_ITEM_DECIDED");
        MvcResult noReason = decideItem(item2, "REVOKE", null, reviewer);
        assertThat(noReason.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(noReason).get("code").asString()).isEqualTo("REASON_REQUIRED");
        MvcResult past = openReview(List.of("ID1"), "2020-01-01T00:00:00Z", ADMIN);
        assertThat(past.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(past).get("code").asString()).isEqualTo("ACCESS_REVIEW_DUE_IN_PAST");

        MvcResult overlapping = openReview(List.of("ID1"), inHours(24), ADMIN);
        assertThat(body(overlapping).get("counts").get("total").asInt()).as("the kept one is reviewed again")
                .isEqualTo(1);
        assertThat(body(overlapping).get("skippedUnderReview").asInt()).as("the pending one is not").isEqualTo(1);

        MvcResult closed = closeReview(first, ADMIN);
        assertThat(closed.getResponse().getStatus()).isEqualTo(200);
        MvcResult closedAgain = closeReview(first, ADMIN);
        assertThat(closedAgain.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(closedAgain).get("code").asString()).isEqualTo("ACCESS_REVIEW_CLOSED");
        MvcResult afterClosure = decideItem(item2, "KEEP", null, reviewer);
        assertThat(afterClosure.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(afterClosure).get("code").asString()).isEqualTo("ACCESS_REVIEW_CLOSED");

        // The assignment ended before anyone decided its item in the overlapping review.
        String overlappingId = body(overlapping).get("id").asString();
        String reReviewed = itemOf(overlappingId, "u-id-1", reviewer);
        String assignment1 = body(getAs("/v1/access-review-items?campaignId=" + overlappingId, reviewer))
                .get("items").get(0).get("assignmentId").asString();
        postAs("/v1/role-assignments/" + assignment1 + "/revocations", Map.of("reason", "moved"), ADMIN);
        MvcResult ended = decideItem(reReviewed, "KEEP", null, reviewer);
        assertThat(ended.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(ended).get("code").asString()).isEqualTo("ACCESS_REVIEW_ASSIGNMENT_ENDED");

        // Two administrators open the same review at the same moment: no 500, and the assignment is reviewed once.
        assign("u-id-3", "REFDATA_MAKER", List.of("ID3"), ADMIN);
        String payload = json.writeValueAsString(Map.of("title", "Concurrent review", "scopes",
                scopes(List.of("ID3")), "dueAt", inHours(48), "reason", "concurrency test"));
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<MvcResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/v1/access-reviews").with(ADMIN).header("X-Channel", "BACKOFFICE")
                            .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();
                }));
            }
            start.countDown();
            int total = 0;
            int skipped = 0;
            for (java.util.concurrent.Future<MvcResult> f : futures) {
                MvcResult r = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
                total += body(r).get("counts").get("total").asInt();
                skipped += body(r).get("skippedUnderReview").asInt();
            }
            assertThat(total).isEqualTo(1);
            assertThat(skipped).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Tag("SEC-007")
    @DisplayName("SEC-007: the overdue listing shows the configured review period (default P3M)")
    void overdueAssignmentsShowsConfiguredPeriod() throws Exception {
        MvcResult r = getAs("/v1/access-reviews/overdue-assignments", ADMIN);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(r).get("reviewPeriod").asString()).isEqualTo("P3M");
        assertThat(body(r).get("evaluatedAt").asString()).isNotBlank();
        assertThat(body(r).get("assignments").isArray()).isTrue();
        assertThat(getAs("/v1/access-reviews/overdue-assignments", as("u-ov-nobody")).getResponse().getStatus())
                .isEqualTo(403);

        // An assignment granted four months ago and never reviewed is overdue; a KEEP decision takes it off the list.
        String old = java.util.UUID.randomUUID().toString();
        db.sql("""
                insert into role_assignment (id, subject_id, subject_username, role_code, regions, centres, modules,
                    valid_from, status, reason, assigned_by, assigned_at)
                values (cast(:id as uuid), 'u-ov-old', 'u-ov-old.name', 'REFDATA_MAKER', '{OV1}', '{*}', '{*}',
                    now() - interval '4 months', 'ACTIVE', 'granted long ago', 'u-admin', now() - interval '4 months')""")
                .param("id", old).update();
        assign("u-ov-rev", "ACCESS_REVIEWER", List.of("OV1", "OV9"), ADMIN);
        RequestPostProcessor reviewer = as("u-ov-rev");
        JsonNode overdue = overdueFor(old, reviewer);
        assertThat(overdue.get("lastReviewedAt").isNull()).isTrue();
        assertThat(overdue.get("pendingItemId").isNull()).isTrue();
        assertThat(overdue.get("reviewDueSince").asString()).isNotBlank();

        String id = openReview(List.of("OV1"));
        String item = itemOf(id, "u-ov-old", reviewer);
        assertThat(overdueFor(old, reviewer).get("pendingItemId").asString()).isEqualTo(item);
        assertThat(decideItem(item, "KEEP", null, reviewer).getResponse().getStatus()).isEqualTo(200);
        assertThat(body(getAs("/v1/access-reviews/overdue-assignments", reviewer)).get("assignments").valueStream()
                .map(a -> a.get("assignment").get("id").asString())).doesNotContain(old);
    }

    private JsonNode overdueFor(String assignmentId, RequestPostProcessor who) throws Exception {
        JsonNode list = body(getAs("/v1/access-reviews/overdue-assignments", who)).get("assignments");
        return list.valueStream().filter(a -> a.get("assignment").get("id").asString().equals(assignmentId))
                .findFirst().orElseThrow(() -> new AssertionError("not overdue: " + assignmentId));
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

    // ------------------------------------------------------------------ tester regression tests (defects, round 1)

    @Test
    @Tag("SEC-007")
    @Tag("defect")
    @DisplayName("SEC-007 defect: an absurdly distant dueAt is refused with 4xx, not a 500")
    void accessReviewAbsurdDueDateIsRefusedNotServerError() throws Exception {
        MvcResult r = openReview(List.of("DF1"), "+999999999-01-01T00:00:00Z", ADMIN);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(400, 422);
    }

    @Test
    @Tag("SEC-022")
    @Tag("SEC-007")
    @Tag("defect")
    @DisplayName("SEC-022 defect: a NUL character in a review title, reason or decision reason is a 4xx, not a 500")
    void accessReviewNulCharacterIsRefusedNotServerError() throws Exception {
        MvcResult r = postAs("/v1/access-reviews", Map.of("title", "ab\0cd", "scopes", scopes(List.of("DF2")),
                "dueAt", inHours(48), "reason", "quarterly review"), ADMIN);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(400, 422);
    }

    @Test
    @Tag("SEC-007")
    @Tag("SEC-022")
    @Tag("defect")
    @DisplayName("SEC-007 defect: decision must be the string KEEP or REVOKE; a number must not revoke access")
    void accessReviewNumericDecisionIsRefused() throws Exception {
        assign("u-df-rev", "ACCESS_REVIEWER", List.of("DF3"), ADMIN);
        assign("u-df-sub", "REFDATA_MAKER", List.of("DF3"), ADMIN);
        RequestPostProcessor reviewer = as("u-df-rev");
        String id = openReview(List.of("DF3"));
        String item = itemOf(id, "u-df-sub", reviewer);
        MvcResult r = postAs("/v1/access-review-items/" + item + "/decisions",
                Map.of("decision", 1, "reason", "numeric decision"), reviewer);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(myGrants("u-df-sub")).as("nothing was revoked").hasSize(1);
    }

    @Test
    @Tag("SEC-007")
    @Tag("DAT-002")
    @Tag("defect")
    @DisplayName("DAT-002 defect: the database refuses a campaign whose reason breaks the 3-character rule")
    void accessReviewDatabaseRefusesShortReason() {
        assertThatThrownBy(() -> db.sql("""
                insert into access_review_campaign (id, title, regions, centres, modules, due_at, reason, status,
                       created_by, created_at)
                values (gen_random_uuid(), 'Direct insert', array['DF4'], array['*'], array['*'],
                        now() + interval '1 day', 'x', 'OPEN', 'u-direct', now())""").update())
                .isInstanceOf(DataAccessException.class);
    }

    // ------------------------------------------------------------------ coder fixes (round 2)

    @Test
    @Tag("SEC-022")
    @Tag("SEC-007")
    @DisplayName("SEC-022: request hardening applies to every endpoint: NUL text, out-of-range date-times, numeric enums")
    void requestHardeningAppliesToEveryEndpoint() throws Exception {
        Map<String, Object> nulReason = new HashMap<>(assignment("u-hd-sub", "REFDATA_MAKER", List.of("HD1")));
        nulReason.put("reason", "integration\u0000test");
        MvcResult nul = postAs("/v1/role-assignments", nulReason, ADMIN);
        assertThat(nul.getResponse().getStatus()).as("NUL in a pre-existing endpoint").isEqualTo(400);
        assertThat(body(nul).get("code").asString()).isEqualTo("VALIDATION_FAILED");

        Map<String, Object> farValidTo = new HashMap<>(assignment("u-hd-sub", "REFDATA_MAKER", List.of("HD1")));
        farValidTo.put("validTo", "+999999999-01-01T00:00:00Z");
        assertThat(postAs("/v1/role-assignments", farValidTo, ADMIN).getResponse().getStatus())
                .as("out-of-range validTo").isEqualTo(400);

        assertThat(openReview(List.of("HD1"), "+10000-01-01T00:00:00Z", ADMIN).getResponse().getStatus())
                .as("five-digit year is not RFC 3339").isEqualTo(400);
        MvcResult latest = openReview(List.of("HD2"), "9999-12-31T23:59:59Z", ADMIN);
        assertThat(latest.getResponse().getStatus()).as(latest.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(latest).get("dueAt").asString()).isEqualTo("9999-12-31T23:59:59Z");
        String latestId = body(latest).get("id").asString();
        assertThat(postAs("/v1/access-reviews/" + latestId + "/closures", Map.of("reason", "done\u0000now"), ADMIN)
                .getResponse().getStatus()).as("NUL in a closure reason").isEqualTo(400);

        assign("u-hd-rev", "ACCESS_REVIEWER", List.of("HD3"), ADMIN);
        assign("u-hd-sub3", "REFDATA_MAKER", List.of("HD3"), ADMIN);
        RequestPostProcessor reviewer = as("u-hd-rev");
        String item = itemOf(openReview(List.of("HD3")), "u-hd-sub3", reviewer);
        assertThat(decideItem(item, "1", "numeric text decision", reviewer).getResponse().getStatus())
                .as("an index as text is not an enum name").isEqualTo(400);
        assertThat(postAs("/v1/access-review-items/" + item + "/decisions",
                Map.of("decision", 0, "reason", "numeric decision"), reviewer).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(myGrants("u-hd-sub3")).as("nothing was decided").hasSize(1);
        assertThat(decideItem(item, "KEEP", null, reviewer).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @Tag("SEC-007")
    @Tag("DAT-002")
    @DisplayName("DAT-002: the database sets NOT_REVIEWED / ENDED only on closure and refuses short reasons")
    void accessReviewDatabaseClosureOutcomeGuards() throws Exception {
        assign("u-dg-sub", "REFDATA_MAKER", List.of("DG1"), ADMIN);
        String id = openReview(List.of("DG1"));
        String item = itemOf(id, "u-dg-sub", ADMIN);
        for (String outcome : List.of("NOT_REVIEWED", "ENDED")) {
            assertThatThrownBy(() -> db.sql("update access_review_item set status = :s where id = cast(:id as uuid)")
                    .param("s", outcome).param("id", item).update()).as(outcome + " in an open review")
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("only when the review is closed");
        }
        assertThatThrownBy(() -> db.sql("""
                update access_review_campaign set status = 'CLOSED', closed_by = 'u-direct', closed_at = now(),
                       closure_reason = 'x' where id = cast(:id as uuid)""").param("id", id).update())
                .as("short closure reason").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_campaign_closure_reason");

        MvcResult closed = closeReview(id, ADMIN);
        assertThat(closed.getResponse().getStatus()).as(closed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(closed).get("counts").get("notReviewed").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ tester regression tests (defects, round 2)

    @Test
    @Tag("SEC-007")
    @Tag("SEC-022")
    @Tag("defect")
    @DisplayName("SEC-022 defect: a decision must be exactly KEEP or REVOKE; padding or a control character is refused")
    void accessReviewDecisionWithPaddingOrControlCharacterIsRefused() throws Exception {
        assign("u-r2a-rev", "ACCESS_REVIEWER", List.of("R2A"), ADMIN);
        assign("u-r2a-sub", "REFDATA_MAKER", List.of("R2A"), ADMIN);
        RequestPostProcessor reviewer = as("u-r2a-rev");
        String id = openReview(List.of("R2A"));
        String item = itemOf(id, "u-r2a-sub", reviewer);
        for (String padded : List.of("KEEP ", " KEEP", "KEEP\0")) {
            MvcResult r = postAs("/v1/access-review-items/" + item + "/decisions",
                    Map.of("decision", padded, "reason", "padded decision"), reviewer);
            assertThat(r.getResponse().getStatus()).as("decision [" + padded.replace('\0', '0') + "] -> "
                    + r.getResponse().getContentAsString()).isEqualTo(400);
        }
    }

    @Test
    @Tag("SEC-007")
    @Tag("SEC-022")
    @Tag("defect")
    @DisplayName("SEC-022 defect: dueAt is a date-time string; a JSON number (epoch) is refused with 400")
    void accessReviewNumericDueAtIsRefused() throws Exception {
        MvcResult r = postAs("/v1/access-reviews", Map.of("title", "Numeric due", "scopes", scopes(List.of("R2B")),
                "dueAt", 4102444800L, "reason", "numeric due date"), ADMIN);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
    }

    @Test
    @Tag("SEC-007")
    @Tag("defect")
    @DisplayName("SEC-007 defect: the create response echoes the stored dueAt (microsecond precision), same as the GET")
    void accessReviewCreateResponseMatchesStoredDueAt() throws Exception {
        MvcResult created = openReview(List.of("R2C"), "2099-01-01T00:00:00.123456789Z", ADMIN);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String id = body(created).get("id").asString();
        String stored = body(getAs("/v1/access-reviews/" + id, ADMIN)).get("dueAt").asString();
        assertThat(body(created).get("dueAt").asString()).isEqualTo(stored);
    }

    @Test
    @Tag("CC-003")
    @Tag("SEC-022")
    @Tag("defect")
    @DisplayName("CC-003 defect: a fractional maxAmountMinor is refused, not silently truncated")
    void approvalLimitFractionalAmountIsRefused() throws Exception {
        MvcResult role = postAs("/v1/roles", Map.of("code", "IT_R2_LIMITS", "name", Map.of("nl", "س", "en", "R2"),
                "permissions", List.of("iam.role.read"), "reason", "round 2 probe"), ADMIN);
        assertThat(role.getResponse().getStatus()).as(role.getResponse().getContentAsString()).isEqualTo(201);
        String raw = "{\"version\":1,\"reason\":\"fraction probe\",\"limits\":[{\"limitType\":\"REFUND_AMOUNT\","
                + "\"maxAmountMinor\":5.9,\"currency\":\"XTS\"}]}";
        MvcResult r = putAs("/v1/roles/IT_R2_LIMITS/approval-limits", json.readTree(raw), ADMIN);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
    }

    @Test
    @Tag("SEC-007")
    @Tag("DAT-002")
    @Tag("defect")
    @DisplayName("DAT-002 defect: the database refuses a campaign reason that the domain would reject (tabs only)")
    void accessReviewDatabaseRefusesWhitespaceOnlyReason() {
        assertThatThrownBy(() -> db.sql("""
                insert into access_review_campaign (id, title, regions, centres, modules, due_at, reason, status,
                       created_by, created_at)
                values (gen_random_uuid(), 'Direct insert', array['R2D'], array['*'], array['*'],
                        now() + interval '1 day', E'\t\t\t', 'OPEN', 'u-direct', now())""").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Tag("SEC-022")
    @DisplayName("SEC-022: a status query parameter must be exactly an enum name; padding is refused with 400")
    void enumQueryParameterWithPaddingIsRefused() throws Exception {
        for (String status : List.of("OPEN ", " OPEN", "open", "OPEN\0")) {
            MvcResult r = mvc.perform(get("/v1/access-reviews").param("status", status).with(ADMIN)
                    .header("X-Channel", "BACKOFFICE").accept(MediaType.APPLICATION_JSON,
                            MediaType.APPLICATION_PROBLEM_JSON)).andReturn();
            assertThat(r.getResponse().getStatus()).as("status [" + status.replace('\0', '0') + "]").isEqualTo(400);
        }
        assertThat(getAs("/v1/access-reviews?status=OPEN", ADMIN).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @Tag("CC-003")
    @Tag("SEC-022")
    @DisplayName("CC-003: maxAmountMinor as a string or in exponent form is refused, never parsed or converted")
    void approvalLimitAmountMustBeJsonInteger() throws Exception {
        MvcResult role = postAs("/v1/roles", Map.of("code", "IT_R3_LIMITS", "name", Map.of("nl", "R3", "en", "R3"),
                "permissions", List.of("iam.role.read"), "reason", "round 3 probe"), ADMIN);
        assertThat(role.getResponse().getStatus()).as(role.getResponse().getContentAsString()).isEqualTo(201);
        for (String amount : List.of("\"7\"", "1e3", "7.0", "true")) {
            String raw = "{\"version\":1,\"reason\":\"amount probe\",\"limits\":[{\"limitType\":\"REFUND_AMOUNT\","
                    + "\"maxAmountMinor\":" + amount + ",\"currency\":\"XTS\"}]}";
            MvcResult r = putAs("/v1/roles/IT_R3_LIMITS/approval-limits", json.readTree(raw), ADMIN);
            assertThat(r.getResponse().getStatus()).as(amount + " -> " + r.getResponse().getContentAsString())
                    .isEqualTo(400);
        }
    }

    @Test
    @Tag("SEC-007")
    @Tag("SEC-022")
    @DisplayName("SEC-022: a text field given as a number or boolean is refused, not stored as text")
    void textFieldsMustBeJsonStrings() throws Exception {
        for (Object title : List.of(12345, true, 1.5)) {
            MvcResult r = postAs("/v1/access-reviews", Map.of("title", title, "scopes", scopes(List.of("R3T")),
                    "dueAt", "2099-01-01T00:00:00Z", "reason", "typed title"), ADMIN);
            assertThat(r.getResponse().getStatus()).as(title + " -> " + r.getResponse().getContentAsString())
                    .isEqualTo(400);
        }
    }

    @Test
    @Tag("DAT-002")
    @DisplayName("DAT-002: iam_strip treats exactly the characters Java String.strip() treats as whitespace")
    void databaseStripMatchesJavaWhitespace() {
        List<Integer> stripped = db.sql("""
                select cp from generate_series(1, 65535) cp
                where cp not between 55296 and 57343 and iam_strip(chr(cp)) = ''
                order by cp""").query(Integer.class).list();
        List<Integer> java = new java.util.ArrayList<>();
        for (int cp = 1; cp <= 0xFFFF; cp++) {
            if ((cp < 0xD800 || cp > 0xDFFF) && Character.isWhitespace(cp)) {
                java.add(cp);
            }
        }
        assertThat(stripped).isEqualTo(java).contains(0x09, 0x2003).doesNotContain(0xA0, 0x2007, 0x202F);
    }

    @Test
    @Tag("INT-003")
    @Tag("CH-053")
    @DisplayName("INT-003/CH-053: an unsupported method is 405 with Allow, a non-JSON body 415; never 500")
    void unsupportedMethodAndMediaType() throws Exception {
        MvcResult delete = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/v1/roles").with(ADMIN).accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        assertThat(delete.getResponse().getStatus()).isEqualTo(405);
        assertThat(delete.getResponse().getHeader("Allow")).contains("GET").contains("POST");
        assertThat(body(delete).get("code").asString()).isEqualTo("METHOD_NOT_ALLOWED");

        MvcResult arabic = mvc.perform(put("/v1/roles").with(ADMIN).header("Accept-Language", "ar")
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)).andReturn();
        assertThat(arabic.getResponse().getStatus()).isEqualTo(405);
        assertThat(body(arabic).get("detail").asString()).isEqualTo("هذا الإجراء غير متاح لهذا المورد.");

        MvcResult text = mvc.perform(post("/v1/roles").with(ADMIN).contentType(MediaType.TEXT_PLAIN).content("x")
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)).andReturn();
        assertThat(text.getResponse().getStatus()).isEqualTo(415);
        assertThat(body(text).get("code").asString()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001 (ADR-067): a customer token gets only the CUSTOMER grant without scopes, whatever is "
            + "assigned to the subject; the role cannot be assigned")
    void customerGrant() throws Exception {
        assign("u-customer", "REFDATA_MAKER", List.of("R1"), ADMIN);
        RequestPostProcessor customer = jwt().jwt(j -> j.subject("u-customer")
                .claim("preferred_username", "784199012345671").claim("subject_type", "CUSTOMER")
                .claim("national_id", "784199012345671"));

        MvcResult r = getAs("/v1/me/authorisation", customer);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).get("grants")).singleElement().satisfies(g -> {
            assertThat(g.get("roleCode").asString()).isEqualTo("CUSTOMER");
            assertThat(g.get("permissions")).extracting(p -> p.asString()).containsExactlyInAnyOrder(
                    "customer.trafficfile.read", "customer.violation.read", "customer.transaction.read",
                    "customer.payment.make", "customer.violation.object", "customer.instalment.request",
                    "customer.notification.read", "customer.notification.preferences");
            assertThat(g.get("scopes").get("regions")).isEmpty();
            assertThat(g.get("scopes").get("centres")).isEmpty();
            assertThat(g.get("scopes").get("modules")).isEmpty();
        });
        assertThat(getAs("/v1/roles", customer).getResponse().getStatus()).isEqualTo(403);
        assertThat(myGrants("u-customer")).singleElement()
                .satisfies(g -> assertThat(g.get("roleCode").asString()).isEqualTo("REFDATA_MAKER"));

        MvcResult refused = postAs("/v1/role-assignments", assignment("u-staff-cust", "CUSTOMER", List.of("R1")),
                ADMIN);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(refused).get("code").asString()).isEqualTo("ROLE_NOT_ASSIGNABLE");
    }
}

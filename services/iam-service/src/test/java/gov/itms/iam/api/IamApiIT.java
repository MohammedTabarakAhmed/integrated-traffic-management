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

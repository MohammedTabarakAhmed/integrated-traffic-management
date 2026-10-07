package gov.itms.workflow.api;

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
 * End-to-end slice test of workflows: HTTP → security → service → PostgreSQL (V1 guards) → audit and event outboxes,
 * with every request and response validated against workflow-service.v1.yaml. Tests share one database, so each uses
 * its own workflow code and references. Token-claim grants are national, one per role in the {@code roles} claim.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.workflow.escalation.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("CC-060")
class WorkflowApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final AtomicInteger CODES = new AtomicInteger();
    private static final List<String> IDEMPOTENT = List.of("/decisions", "/returns", "/customer-responses",
            "/cancellations");
    private static OpenApiInteractionValidator contract;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/workflow-service.v1.yaml").build();
    }

    private static RequestPostProcessor admin() {
        return user("u-admin", "admin", "WORKFLOW_ADMIN", "workflow.definition.manage", "workflow.definition.read");
    }

    private static RequestPostProcessor starter() {
        return user("svc-vehicle", "vehicle", "WORKFLOW_STARTER", "workflow.item.create");
    }

    private static RequestPostProcessor officer(String sub) {
        return user(sub, sub, "CASE_OFFICER", "workflow.item.work");
    }

    private static RequestPostProcessor approver() {
        return user("u-approver", "approver", "CASE_APPROVER", "workflow.item.work");
    }

    private static RequestPostProcessor supervisor() {
        return user("u-super", "super", "CASE_SUPERVISOR", "workflow.item.supervise");
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
        boolean idempotent = path.equals("/v1/workflow-definitions") || path.equals("/v1/work-items")
                || IDEMPOTENT.stream().anyMatch(path::endsWith);
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

    private static String newCode() {
        return "WF" + CODES.incrementAndGet() + "_" + Long.toString(System.nanoTime() % 1_000_000, 36).toUpperCase();
    }

    private static Map<String, Object> step(String code, String role, String assignment, String sla,
                                            Map<String, String> outcomes) {
        return Map.of("code", code, "kind", "APPROVAL", "nameNl", "خطوة", "nameEn", "Step " + code, "role", role,
                "assignment", assignment, "sla", sla, "escalationRole", "CASE_SUPERVISOR", "outcomes", outcomes);
    }

    /** REVIEW (CASE_OFFICER) → APPROVE (CASE_APPROVER) → END; REVIEW may also reject to END. */
    private String published(String assignment) throws Exception {
        String code = newCode();
        Map<String, Object> request = Map.of("code", code, "serviceCode", "VL_RENEW", "nameNl", "مراجعة",
                "nameEn", "Review", "firstStep", "REVIEW",
                "steps", List.of(step("REVIEW", "CASE_OFFICER", assignment, "PT24H",
                                Map.of("PASSED", "APPROVE", "REJECTED", "END")),
                        step("APPROVE", "CASE_APPROVER", "MANUAL", "PT4H", Map.of("APPROVED", "END"))),
                "reason", "first version");
        MvcResult r = postAs("/v1/workflow-definitions", request, admin());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return code;
    }

    private JsonNode started(String workflow, String reference, String customerId) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of("workflowCode", workflow, "reference", reference,
                "serviceCode", "VL_RENEW", "region", "NORTH", "centre", "C1"));
        request.put("customerId", customerId);
        MvcResult r = postAs("/v1/work-items", request, starter());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r);
    }

    @Test
    @DisplayName("publish, start, claim, decide to the next queue and complete; events, audit and guards")
    void lifecycle() throws Exception {
        String workflow = published("MANUAL");
        JsonNode item = started(workflow, "APP-" + workflow.replace('_', '-'), null);
        String id = item.get("id").asString();
        assertThat(item.get("stepCode").asString()).isEqualTo("REVIEW");
        assertThat(item.get("queueRole").asString()).isEqualTo("CASE_OFFICER");
        assertThat(item.get("assignee").isNull()).isTrue();

        assertThat(code(postAs("/v1/work-items/" + id + "/decisions", Map.of("outcome", "PASSED"),
                officer("u-a")))).isEqualTo("NOT_ASSIGNEE");
        assertThat(body(postAs("/v1/work-items/" + id + "/claims", null, officer("u-a"))).get("assignee")
                .asString()).isEqualTo("u-a");
        assertThat(code(postAs("/v1/work-items/" + id + "/claims", null, officer("u-b"))))
                .isEqualTo("WORK_ITEM_ASSIGNED");
        assertThat(code(postAs("/v1/work-items/" + id + "/decisions", Map.of("outcome", "MAYBE"), officer("u-a"))))
                .isEqualTo("OUTCOME_INVALID");
        JsonNode moved = body(postAs("/v1/work-items/" + id + "/decisions",
                Map.of("outcome", "PASSED", "reason", "documents complete"), officer("u-a")));
        assertThat(moved.get("stepCode").asString()).isEqualTo("APPROVE");
        assertThat(moved.get("queueRole").asString()).isEqualTo("CASE_APPROVER");

        postAs("/v1/work-items/" + id + "/claims", null, approver());
        JsonNode done = body(postAs("/v1/work-items/" + id + "/decisions", Map.of("outcome", "APPROVED"),
                approver()));
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("outcome").asString()).isEqualTo("APPROVED");

        JsonNode history = body(getAs("/v1/work-items/" + id + "/history", approver())).get("items");
        assertThat(history.findValuesAsString("action")).containsSubsequence("CREATED", "CLAIMED", "DECIDED",
                "CLAIMED", "DECIDED");
        assertThat(db.sql("select event_type from event_outbox where subject = :s order by occurred_at")
                .param("s", item.get("reference").asString()).query(String.class).list())
                .containsExactly("itms.workflow.item-created", "itms.workflow.step-entered",
                        "itms.workflow.item-completed");
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :id and action = 'WORK_ITEM_DECIDED'")
                .param("id", id).query(Long.class).single()).isEqualTo(2);

        assertThatThrownBy(() -> db.sql("update work_item set assignee = 'x' where id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("closed work item");
        assertThatThrownBy(() -> db.sql("delete from work_item where id = cast(:id as uuid)").param("id", id)
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("never deleted");
        assertThatThrownBy(() -> db.sql("delete from work_item_history where item_id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("update workflow_definition set name_en = 'x' where code = :c")
                .param("c", workflow).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("never change");
    }

    @Test
    @Tag("CC-061")
    @Tag("CC-063")
    @DisplayName("members join the queue; round robin assigns; personal and team lists; supervisor (bulk) assignment")
    void queuesAndLists() throws Exception {
        String workflow = published("ROUND_ROBIN");
        String a = "u-qa-" + workflow;
        assertThat(postAs("/v1/queue-memberships/me", Map.of("available", true), officer(a)).getResponse()
                .getStatus()).isEqualTo(200);
        JsonNode memberships = body(getAs("/v1/queue-memberships/me", officer(a))).get("items");
        assertThat(memberships.get(0).get("role").asString()).isEqualTo("CASE_OFFICER");
        assertThat(memberships.get(0).get("available").asBoolean()).isTrue();

        JsonNode first = started(workflow, "RR1-" + workflow.replace('_', '-'), null);
        assertThat(first.get("assignee").isNull()).as("someone available was picked").isFalse();

        // From now on a is the only available member we control; leave so manual assignment is visible.
        postAs("/v1/queue-memberships/me", Map.of("available", false), officer(a));
        String manual = published("MANUAL");
        String one = started(manual, "M1-" + manual.replace('_', '-'), null).get("id").asString();
        String two = started(manual, "M2-" + manual.replace('_', '-'), null).get("id").asString();
        String missing = UUID.randomUUID().toString();

        MvcResult bulk = postAs("/v1/work-items/bulk-assignments",
                Map.of("itemIds", List.of(one, two, missing), "assignee", a, "reason", "spread the work"),
                supervisor());
        assertThat(bulk.getResponse().getStatus()).as(bulk.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode results = body(bulk).get("items");
        assertThat(results.findValues("done").stream().map(JsonNode::asBoolean).toList())
                .containsExactly(true, true, false);
        assertThat(results.get(2).get("problem").asString()).isEqualTo("WORK_ITEM_NOT_FOUND");
        assertThat(code(postAs("/v1/work-items/" + one + "/assignments", Map.of("assignee", "u-nobody"),
                supervisor()))).isEqualTo("ASSIGNEE_NOT_IN_QUEUE");
        assertThat(postAs("/v1/work-items/" + one + "/assignments", Map.of("assignee", a), officer(a))
                .getResponse().getStatus()).as("officers cannot assign").isEqualTo(403);

        JsonNode personal = body(getAs("/v1/work-items?list=PERSONAL&limit=200", officer(a))).get("items");
        assertThat(personal.findValuesAsString("id")).contains(one, two);
        JsonNode team = body(getAs("/v1/work-items?list=TEAM&serviceCode=VL_RENEW&limit=200", officer("u-other")))
                .get("items");
        assertThat(team.findValuesAsString("id")).contains(one, two);
        assertThat(getAs("/v1/work-items?list=TEAM&limit=0", officer(a)).getResponse().getStatus()).isEqualTo(422);

        assertThat(body(postAs("/v1/work-items/" + one + "/releases", Map.of("reason", "on leave"), supervisor()))
                .get("assignee").isNull()).isTrue();
    }

    @Test
    @Tag("CC-064")
    @Tag("CC-065")
    @DisplayName("internal notes are append-only; a return pauses the SLA until the customer responds")
    void notesAndReturns() throws Exception {
        String workflow = published("MANUAL");
        String customer = "c-" + workflow;
        JsonNode item = started(workflow, "RET-" + workflow.replace('_', '-'), customer);
        String id = item.get("id").asString();
        postAs("/v1/work-items/" + id + "/claims", null, officer("u-a"));

        MvcResult note = postAs("/v1/work-items/" + id + "/notes",
                Map.of("text", "called the customer", "attachmentIds", List.of("doc-1")), officer("u-a"));
        assertThat(note.getResponse().getStatus()).as(note.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(getAs("/v1/work-items/" + id + "/notes", officer("u-a"))).get("items")).hasSize(1);
        RequestPostProcessor customerToken = jwt().jwt(j -> j.subject(customer).claim("roles", List.of("CUSTOMER")));
        assertThat(getAs("/v1/work-items/" + id + "/notes", customerToken).getResponse().getStatus())
                .as("notes are never shown to customers").isEqualTo(403);
        assertThatThrownBy(() -> db.sql("update work_item_note set text = 'x' where item_id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        JsonNode returned = body(postAs("/v1/work-items/" + id + "/returns",
                Map.of("requestedInformation", "please upload the insurance policy"), officer("u-a")));
        assertThat(returned.get("status").asString()).isEqualTo("RETURNED");
        assertThat(returned.get("pausedAt").isNull()).isFalse();
        assertThat(code(postAs("/v1/work-items/" + id + "/decisions", Map.of("outcome", "PASSED"), officer("u-a"))))
                .isEqualTo("WORK_ITEM_RETURNED");
        RequestPostProcessor stranger = jwt().jwt(j -> j.subject("c-stranger").claim("roles", List.of("CUSTOMER")));
        assertThat(postAs("/v1/work-items/" + id + "/customer-responses", Map.of("text", "mine"), stranger)
                .getResponse().getStatus()).isEqualTo(403);
        JsonNode resumed = body(postAs("/v1/work-items/" + id + "/customer-responses",
                Map.of("text", "uploaded", "attachmentIds", List.of("doc-2")), customerToken));
        assertThat(resumed.get("status").asString()).isEqualTo("OPEN");
        assertThat(resumed.get("pausedAt").isNull()).isTrue();
        assertThat(db.sql("select event_type from event_outbox where subject = :s order by occurred_at")
                .param("s", item.get("reference").asString()).query(String.class).list())
                .contains("itms.workflow.item-returned", "itms.workflow.item-resumed");
    }

    @Test
    @DisplayName("ADR-032: starting a case twice with the same key replays; another key for the same case conflicts")
    void idempotentStart() throws Exception {
        String workflow = published("MANUAL");
        Map<String, Object> request = Map.of("workflowCode", workflow, "reference", "IDEM-" + workflow.replace('_',
                '-'), "serviceCode", "VL_RENEW", "region", "NORTH");
        String key = "it-" + UUID.randomUUID();
        MvcResult first = call(post("/v1/work-items"), Request.Method.POST, "/v1/work-items", request, starter(), key);
        MvcResult retry = call(post("/v1/work-items"), Request.Method.POST, "/v1/work-items", request, starter(), key);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(retry).get("id").asString()).isEqualTo(body(first).get("id").asString());
        assertThat(code(postAs("/v1/work-items", request, starter()))).isEqualTo("WORK_ITEM_EXISTS");
        String id = body(first).get("id").asString();
        assertThat(body(postAs("/v1/work-items/" + id + "/cancellations", Map.of("reason", "application withdrawn"),
                starter())).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(code(postAs("/v1/work-items/" + id + "/cancellations", Map.of("reason", "again please"),
                starter()))).isEqualTo("WORK_ITEM_CLOSED");
    }
}

package gov.itms.refdata.api;

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
 * Every request and response is validated against reference-data-service.v1.yaml (API contract test).
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        // Grants from token claims: this test covers reference data, not where grants come from (ADR-022 is
        // covered by the platform tests and by iam-service).
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
class ReferenceDataApiIT {

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
        contract = OpenApiInteractionValidator.createFor("/openapi/reference-data-service.v1.yaml").build();
    }

    // ------------------------------------------------------------------ actors (permissions as the IdP would issue)

    private static RequestPostProcessor maker() {
        return user("u-maker", "refdata.maker", "refdata.read", "refdata.change.submit", "refdata.change.read");
    }

    private static RequestPostProcessor checker() {
        return user("u-checker", "refdata.checker", "refdata.read", "refdata.change.read", "refdata.change.approve",
                "refdata.history.read");
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
        builder.with(who).header("X-Channel", "BACKOFFICE").header("X-Correlation-Id", "it-correlation-0001")
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        if (payload != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(payload);
        }
        MvcResult result = mvc.perform(builder).andReturn();

        SimpleRequest.Builder req = new SimpleRequest.Builder(method, path).withHeader("Authorization", "Bearer t");
        if (payload != null) {
            req.withContentType("application/json").withBody(payload);
        }
        int qs = path.indexOf('?');
        if (qs > 0) {
            req = new SimpleRequest.Builder(method, path.substring(0, qs)).withHeader("Authorization", "Bearer t");
            for (String pair : path.substring(qs + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                req.withQueryParam(kv[0], kv[1]);
            }
        }
        String requestPath = qs > 0 ? path.substring(0, qs) : path;
        SimpleResponse response = SimpleResponse.Builder
                .status(result.getResponse().getStatus())
                .withContentType(result.getResponse().getContentType())
                .withBody(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .build();
        // Responses are always checked. Requests are checked on success paths only: negative tests send
        // deliberately invalid requests to prove the service rejects them.
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

    private static Map<String, Object> createItem(String code, String en) {
        return Map.of("changeType", "CREATE_ITEM", "listCode", "HOLD_TYPE", "itemCode", code,
                "name", Map.of("nl", "عربي " + en, "en", en), "effectiveFrom", "2026-01-01",
                "externalCodes", Map.of("POLICE", "P-" + code.length()), "reason", "integration test");
    }

    private String submitAndApprove(Map<String, Object> change) throws Exception {
        MvcResult submitted = postAs("/v1/reference-data-changes", change, maker());
        assertThat(submitted.getResponse().getStatus()).isEqualTo(201);
        String id = body(submitted).get("id").asString();
        MvcResult approved = postAs("/v1/reference-data-changes/" + id + "/approvals", null, checker());
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        return id;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Tag("CC-031")
    @Tag("CC-110")
    @DisplayName("CC-031/CC-110: the BRD starting list of 11 hold types is available in both languages")
    void seededHoldTypes() throws Exception {
        JsonNode items = body(getAs("/v1/reference-lists/HOLD_TYPE/items", maker())).get("items");
        List<JsonNode> seeded = items.valueStream()
                .filter(n -> n.get("regionCode").isNull() && !n.get("code").asString().startsWith("IT_")).toList();
        assertThat(seeded).extracting(n -> n.get("code").asString()).containsExactly(
                "UNPAID_FINES", "COURT_ORDER", "POLICE_CIRCULAR", "DRIVING_BAN", "IMPOUND", "MORTGAGE",
                "EXPIRED_INSURANCE", "FAILED_INSPECTION", "ADMINISTRATIVE_BAN", "DECEASED",
                "EXTERNAL_TRAVEL_RESTRICTION");
        assertThat(seeded).allSatisfy(n -> {
            assertThat(n.get("name").get("nl").asString()).isNotBlank().doesNotContain("?");
            assertThat(n.get("name").get("en").asString()).isNotBlank();
        });
        assertThat(body(getAs("/v1/reference-lists", maker())).get("items").get(0).get("code").asString())
                .isEqualTo("HOLD_TYPE");
    }

    @Test
    @Tag("CC-114")
    @Tag("CC-110")
    @DisplayName("CC-114: maker submits, a different checker approves, the item becomes visible")
    void makerCheckerFlow() throws Exception {
        MvcResult submitted = postAs("/v1/reference-data-changes", createItem("IT_FLOW_HOLD", "Flow hold"), maker());
        assertThat(submitted.getResponse().getStatus()).isEqualTo(201);
        JsonNode change = body(submitted);
        assertThat(change.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(submitted.getResponse().getHeader("Location")).endsWith(change.get("id").asString());

        assertThat(getAs("/v1/reference-lists/HOLD_TYPE/items/IT_FLOW_HOLD", maker()).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(body(getAs("/v1/reference-data-changes?status=PENDING_APPROVAL", checker())).get("items")
                .valueStream().map(n -> n.get("id").asString())).contains(change.get("id").asString());

        MvcResult approved = postAs("/v1/reference-data-changes/" + change.get("id").asString() + "/approvals", null,
                checker());
        assertThat(body(approved).get("status").asString()).isEqualTo("APPROVED");
        assertThat(body(approved).get("checker").get("id").asString()).isEqualTo("u-checker");

        JsonNode item = body(getAs("/v1/reference-lists/HOLD_TYPE/items/IT_FLOW_HOLD", maker()));
        assertThat(item.get("name").get("en").asString()).isEqualTo("Flow hold");
        assertThat(item.get("externalCodes").get("POLICE").asString()).isEqualTo("P-12");
        assertThat(item.get("version").asLong()).isEqualTo(1);
    }

    @Test
    @Tag("CC-002")
    @DisplayName("CC-002: the maker cannot approve their own change, even holding the approve permission")
    void segregationOfDuties() throws Exception {
        String id = body(postAs("/v1/reference-data-changes", createItem("IT_SOD_HOLD", "SoD hold"), maker()))
                .get("id").asString();
        RequestPostProcessor makerWithApprove = user("u-maker", "refdata.maker", "refdata.change.approve");

        MvcResult refused = call(post("/v1/reference-data-changes/" + id + "/approvals")
                        .header("Accept-Language", "ar"), Request.Method.POST,
                "/v1/reference-data-changes/" + id + "/approvals", null, makerWithApprove);

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        JsonNode problem = body(refused);
        assertThat(problem.get("code").asString()).isEqualTo("SEGREGATION_OF_DUTIES");
        assertThat(problem.get("detail").asString()).contains("شخص مخوّل آخر"); // Arabic message (CC-010)
        assertThat(problem.get("correlationId").asString()).isEqualTo("it-correlation-0001");

        // Defence in depth: the database refuses checker = maker too.
        assertThatThrownBy(() -> db.sql("""
                update reference_change set status = 'APPROVED', checker_id = maker_id, decided_at = now()
                 where id = cast(:id as uuid)""").param("id", id).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: no token → 401; token without the permission → 403 problem")
    void authorisation() throws Exception {
        assertThat(mvc.perform(get("/v1/reference-lists")).andReturn().getResponse().getStatus()).isEqualTo(401);

        MvcResult forbidden = postAs("/v1/reference-data-changes", createItem("IT_NOPERM", "No permission"),
                user("u-reader", "reader", "refdata.read"));
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(forbidden).get("code").asString()).isEqualTo("FORBIDDEN");

        assertThat(getAs("/v1/reference-lists/HOLD_TYPE/items/COURT_ORDER/history", maker()).getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @Tag("DAT-002")
    @Tag("CC-121")
    @DisplayName("DAT-002/CC-121: an approved update creates a new version; history shows both with validity periods")
    void historyAfterUpdate() throws Exception {
        submitAndApprove(createItem("IT_HIST_HOLD", "History hold"));
        Map<String, Object> update = Map.of("changeType", "UPDATE_ITEM", "listCode", "HOLD_TYPE",
                "itemCode", "IT_HIST_HOLD", "name", Map.of("nl", "عربي محدث", "en", "History hold v2"),
                "effectiveFrom", "2026-01-01", "baseVersion", 1, "reason", "rename for clarity");
        String updateId = submitAndApprove(update);

        JsonNode versions = body(getAs("/v1/reference-lists/HOLD_TYPE/items/IT_HIST_HOLD/history", checker()))
                .get("items");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("name").get("en").asString()).isEqualTo("History hold");
        assertThat(versions.get(0).get("validTo").isNull()).isFalse();
        assertThat(versions.get(1).get("name").get("en").asString()).isEqualTo("History hold v2");
        assertThat(versions.get(1).get("version").asLong()).isEqualTo(2);
        assertThat(versions.get(1).get("changeId").asString()).isEqualTo(updateId);
        assertThat(versions.get(1).get("changedBy").asString()).isEqualTo("u-checker");

        // A stale update (based on version 1) is refused.
        MvcResult stale = postAs("/v1/reference-data-changes", Map.of("changeType", "UPDATE_ITEM",
                "listCode", "HOLD_TYPE", "itemCode", "IT_HIST_HOLD", "name", Map.of("nl", "ق", "en", "Stale"),
                "effectiveFrom", "2026-01-01", "baseVersion", 1, "reason", "stale attempt"), maker());
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(stale).get("code").asString()).isEqualTo("STALE_CHANGE");

        assertThatThrownBy(() -> db.sql("delete from reference_item where code = 'IT_HIST_HOLD'").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Tag("CC-120")
    @Tag("CC-122")
    @DisplayName("CC-120/CC-122: changes are audited with actor, channel, IP, correlation, old/new; audit is append-only")
    void auditTrail() throws Exception {
        String id = submitAndApprove(createItem("IT_AUDIT_HOLD", "Audit hold"));

        var rows = db.sql("""
                        select action, actor_id, channel, ip_address, correlation_id, reason,
                               old_value is not null as has_old, new_value is not null as has_new
                          from audit_outbox
                         where entity_id = :id or entity_id = 'HOLD_TYPE/IT_AUDIT_HOLD'
                         order by occurred_at, action""")
                .param("id", id).query().listOfRows();
        assertThat(rows).extracting(r -> r.get("action")).containsExactlyInAnyOrder(
                "REFDATA_CHANGE_SUBMITTED", "REFDATA_ITEM_CREATED", "REFDATA_CHANGE_APPROVED");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("channel")).isEqualTo("BACKOFFICE");
            assertThat(r.get("ip_address")).isNotNull();
            assertThat(r.get("correlation_id")).isEqualTo("it-correlation-0001");
        });
        assertThat(rows).filteredOn(r -> r.get("action").equals("REFDATA_CHANGE_SUBMITTED"))
                .singleElement().satisfies(r -> assertThat(r.get("actor_id")).isEqualTo("u-maker"));
        assertThat(rows).filteredOn(r -> r.get("action").equals("REFDATA_CHANGE_APPROVED"))
                .singleElement().satisfies(r -> {
                    assertThat(r.get("actor_id")).isEqualTo("u-checker");
                    assertThat(r.get("has_old")).isEqualTo(true);
                    assertThat(r.get("has_new")).isEqualTo(true);
                });

        assertThatThrownBy(() -> db.sql("update audit_outbox set action = 'TAMPERED' where entity_id = :id")
                .param("id", id).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from audit_outbox where entity_id = :id").param("id", id).update())
                .isInstanceOf(DataAccessException.class);
        // The relay may mark a record published exactly once.
        assertThat(db.sql("update audit_outbox set published_at = now() where entity_id = :id")
                .param("id", id).update()).isEqualTo(2);
    }

    @Test
    @Tag("SEC-022")
    @Tag("CH-053")
    @DisplayName("SEC-022/CH-053: invalid input is refused with a plain-language problem")
    void validation() throws Exception {
        MvcResult badCode = postAs("/v1/reference-data-changes", Map.of("changeType", "CREATE_ITEM",
                "listCode", "HOLD_TYPE", "itemCode", "lower case", "name", Map.of("nl", "ق", "en", "X"),
                "effectiveFrom", "2026-01-01", "reason", "bad code"), maker());
        assertThat(badCode.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(badCode).get("code").asString()).isEqualTo("VALIDATION_FAILED");

        MvcResult badDates = postAs("/v1/reference-data-changes", Map.of("changeType", "CREATE_ITEM",
                "listCode", "HOLD_TYPE", "itemCode", "IT_DATES", "name", Map.of("nl", "ق", "en", "X"),
                "effectiveFrom", "2026-06-01", "effectiveTo", "2026-01-01", "reason", "bad dates"), maker());
        assertThat(badDates.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(badDates).get("detail").asString()).isEqualTo("The end date cannot be before the start date.");

        MvcResult missingList = getAs("/v1/reference-lists/NO_SUCH_LIST/items", maker());
        assertThat(missingList.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(missingList).get("detail").asString()).isEqualTo("The list NO_SUCH_LIST does not exist.");
    }

    @Test
    @Tag("CC-112")
    @DisplayName("CC-112: a regional variant overrides the national item when the region is requested")
    void regionalVariant() throws Exception {
        Map<String, Object> regional = new java.util.HashMap<>(Map.of("changeType", "CREATE_ITEM",
                "listCode", "HOLD_TYPE", "itemCode", "DECEASED", "regionCode", "R2",
                "name", Map.of("nl", "متوفى - منطقة 2", "en", "Deceased (region 2)"), "effectiveFrom", "2026-01-01",
                "sortOrder", 100, "reason", "regional wording"));
        submitAndApprove(regional);

        JsonNode national = body(getAs("/v1/reference-lists/HOLD_TYPE/items/DECEASED", maker()));
        JsonNode inRegion = body(getAs("/v1/reference-lists/HOLD_TYPE/items/DECEASED?region=R2", maker()));
        assertThat(national.get("regionCode").isNull()).isTrue();
        assertThat(inRegion.get("name").get("en").asString()).isEqualTo("Deceased (region 2)");

        JsonNode listInRegion = body(getAs("/v1/reference-lists/HOLD_TYPE/items?region=R2", maker())).get("items");
        assertThat(listInRegion.valueStream().filter(n -> n.get("code").asString().equals("DECEASED")))
                .singleElement().satisfies(n -> assertThat(n.get("regionCode").asString()).isEqualTo("R2"));
    }
}

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
import gov.itms.platform.idempotency.IdempotencyCleanupJob;
import gov.itms.transaction.infrastructure.DraftExpiryJob;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 * End-to-end slice test of transactions: HTTP → security → service → PostgreSQL (V2 schema, lifecycle and history
 * triggers, CC-027 index) → audit outbox. Every request and response is validated against transaction-service.v1.yaml.
 * Tests share one database, so each uses its own service codes and subjects.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
class TransactionApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    /** The identity provider and hold-eligibility-service, for eligibility at submission (CC-032). */
    static final EligibilityStub ELIGIBILITY = new EligibilityStub();

    @DynamicPropertySource
    static void eligibilityClient(DynamicPropertyRegistry registry) {
        registry.add("itms.clients.eligibility.base-url", ELIGIBILITY::baseUrl);
        registry.add("itms.clients.eligibility.token-uri", () -> ELIGIBILITY.baseUrl() + "/token");
        registry.add("itms.clients.eligibility.client-id", () -> EligibilityStub.CLIENT_ID);
        registry.add("itms.clients.eligibility.client-secret", () -> EligibilityStub.CLIENT_SECRET);
    }

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final int YEAR = TODAY.getYear();
    private static OpenApiInteractionValidator contract;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    @Autowired
    DataSource dataSource;
    @Autowired
    DraftExpiryJob draftExpiryJob;
    @Autowired
    IdempotencyCleanupJob idempotencyCleanupJob;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/transaction-service.v1.yaml").build();
    }

    // ------------------------------------------------------------------ actors

    private static RequestPostProcessor maker() {
        return user("u-maker", "catalogue.maker", "catalogue.change.submit", "catalogue.change.read");
    }

    private static RequestPostProcessor checker() {
        return user("u-checker", "catalogue.checker", "catalogue.change.read", "catalogue.change.approve");
    }

    /** Counter staff: TRANSACTION_OFFICER permissions (iam V7). */
    private static RequestPostProcessor officer() {
        return user("u-officer", "officer", "transaction.create", "transaction.cancel", "transaction.read");
    }

    private static RequestPostProcessor reader() {
        return user("u-reader", "reader", "transaction.read");
    }

    private static RequestPostProcessor citizen() {
        return user("u-citizen", "citizen");
    }

    private static RequestPostProcessor user(String sub, String username, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", username)
                .claim("roles", List.of(username.toUpperCase().replace('.', '_')))
                .claim("permissions", Arrays.asList(permissions)));
    }

    // ------------------------------------------------------------------ helpers with contract validation

    /** Transaction POSTs get a new Idempotency-Key per call (ADR-032); {@link #postWithKey} chooses one. */
    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who, String channel) throws Exception {
        String key = method == Request.Method.POST && path.startsWith("/v1/transactions")
                ? "it-" + UUID.randomUUID() : null;
        return call(builder, method, path, body, who, channel, key);
    }

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who, String channel, String idempotencyKey) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        builder.with(who).header("X-Correlation-Id", "txn-it-transactions")
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        if (channel != null) {
            builder.header("X-Channel", channel);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (payload != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(payload);
        }
        MvcResult result = mvc.perform(builder).andReturn();

        int qs = path.indexOf('?');
        String requestPath = qs > 0 ? path.substring(0, qs) : path;
        SimpleRequest.Builder req = new SimpleRequest.Builder(method, requestPath)
                .withHeader("Authorization", "Bearer t");
        if (idempotencyKey != null) {
            req.withHeader("Idempotency-Key", idempotencyKey);
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
        return call(get(path), Request.Method.GET, path, null, who, "BACKOFFICE");
    }

    private MvcResult postAs(String path, Object body, RequestPostProcessor who) throws Exception {
        return postVia(path, body, who, "BACKOFFICE");
    }

    private MvcResult postVia(String path, Object body, RequestPostProcessor who, String channel) throws Exception {
        return call(post(path), Request.Method.POST, path, body, who, channel);
    }

    private MvcResult postWithKey(String path, Object body, RequestPostProcessor who, String key) throws Exception {
        return call(post(path), Request.Method.POST, path, body, who, "BACKOFFICE", key);
    }

    /** Defines a service through maker-checker, offered in R1 and R2 on BACKOFFICE and PORTAL. */
    private void define(String code, String feeCode, boolean anyRegion, boolean concurrent) throws Exception {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("code", code);
        s.put("name", Map.of("nl", "خدمة " + code, "en", "Service " + code));
        s.put("module", "VEHICLE");
        s.put("customerTypes", List.of("INDIVIDUAL"));
        s.put("channels", List.of("BACKOFFICE", "PORTAL"));
        s.put("requiredDocuments", List.of());
        s.put("feeCode", feeCode);
        s.put("ruleSetCode", null);
        s.put("workflowCode", null);
        s.put("sla", "P5D");
        s.put("regions", List.of("R1", "R2"));
        s.put("anyRegion", anyRegion);
        s.put("allowsConcurrentOpen", concurrent);
        approve(Map.of("changeType", "CREATE_SERVICE", "serviceCode", code, "service", s, "reason", "test service"));
    }

    private void approve(Map<String, Object> change) throws Exception {
        MvcResult submitted = postAs("/v1/catalogue-changes", change, maker());
        assertThat(submitted.getResponse().getStatus()).as(submitted.getResponse().getContentAsString()).isEqualTo(201);
        MvcResult approved = postAs("/v1/catalogue-changes/" + body(submitted).get("id").asString() + "/approvals",
                null, checker());
        assertThat(approved.getResponse().getStatus()).as(approved.getResponse().getContentAsString()).isEqualTo(200);
    }

    private static Map<String, Object> newTransaction(String serviceCode, String subjectType, String subjectId,
                                                      String homeRegion, String region) {
        Map<String, Object> m = new HashMap<>();
        m.put("serviceCode", serviceCode);
        m.put("subject", Map.of("type", subjectType, "id", subjectId));
        m.put("applicantFileNumber", "TF-1001");
        m.put("homeRegion", homeRegion);
        if (region != null) {
            m.put("region", region);
        }
        return m;
    }

    private MvcResult open(String serviceCode, String subjectId) throws Exception {
        return postAs("/v1/transactions", newTransaction(serviceCode, "VEHICLE", subjectId, "R1", null), officer());
    }

    private String openOk(String serviceCode, String subjectId) throws Exception {
        MvcResult r = open(serviceCode, subjectId);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("reference").asString();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Tag("CC-021")
    @Tag("CC-022")
    @Tag("CC-023")
    @Tag("CC-120")
    @DisplayName("CC-021/CC-022/CC-023/CC-120: open, submit (fee) and cancel; reference, status history and audit")
    void lifecycleWithFee() throws Exception {
        define("ITX_FEE", "VL_RENEW_FEE", true, false);
        MvcResult created = open("ITX_FEE", "VIN-LIFE");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode draft = body(created);
        String ref = draft.get("reference").asString();
        assertThat(ref).isEqualTo("ITX_FEE-R1-" + YEAR + "-0000001");
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/v1/transactions/" + ref);
        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.get("channel").asString()).isEqualTo("BACKOFFICE");
        assertThat(draft.get("paymentRequired").asBoolean()).isTrue();
        assertThat(draft.get("createdBy").get("id").asString()).isEqualTo("u-officer");
        assertThat(draft.get("submittedAt").isNull()).isTrue();

        JsonNode submitted = body(postVia("/v1/transactions/" + ref + "/submissions", null, officer(), "PORTAL"));
        assertThat(submitted.get("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(submitted.get("submittedAt").isNull()).isFalse();
        assertThat(submitted.get("version").asLong()).isEqualTo(3);

        MvcResult cancelled = postAs("/v1/transactions/" + ref + "/cancellations",
                Map.of("reason", "customer withdrew"), officer());
        assertThat(body(cancelled).get("status").asString()).isEqualTo("CANCELLED");

        JsonNode history = body(getAs("/v1/transactions/" + ref + "/status-history", reader())).get("items");
        assertThat(history).hasSize(4);
        assertThat(history.get(0).get("previousStatus").isNull()).isTrue();
        assertThat(history.get(0).get("reason").asString()).isEqualTo("CREATED");
        assertThat(history.get(1).get("previousStatus").asString()).isEqualTo("DRAFT");
        assertThat(history.get(1).get("channel").asString()).isEqualTo("PORTAL");
        assertThat(history.get(1).get("actor").get("id").asString()).isEqualTo("u-officer");
        assertThat(history.get(2).get("reason").asString()).isEqualTo("PAYMENT_REQUIRED");
        assertThat(history.get(2).get("actor").get("id").asString()).isEqualTo("SYSTEM");
        assertThat(history.get(3).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(history.get(3).get("note").asString()).isEqualTo("customer withdrew");
        assertThat(body(getAs("/v1/transactions/" + ref, reader())).get("status").asString()).isEqualTo("CANCELLED");

        List<String> actions = db.sql("select action from audit_outbox where entity_id = :ref order by occurred_at")
                .param("ref", ref).query(String.class).list();
        assertThat(actions).containsExactly("TRANSACTION_CREATED", "TRANSACTION_SUBMITTED", "TRANSACTION_CANCELLED",
                "TRANSACTION_HISTORY_VIEWED", "TRANSACTION_VIEWED");

        // The next one in the same service, region and year.
        assertThat(openOk("ITX_FEE", "VIN-LIFE")).isEqualTo("ITX_FEE-R1-" + YEAR + "-0000002");
        MvcResult again = postAs("/v1/transactions/" + ref + "/submissions", null, officer());
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(again).get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
    }

    @Test
    @Tag("CC-022")
    @DisplayName("CC-022/Q-004: a service without a fee goes to In review; the skip is in the history")
    void zeroFee() throws Exception {
        define("ITX_FREE", null, true, false);
        String ref = openOk("ITX_FREE", "VIN-FREE");
        JsonNode submitted = body(postAs("/v1/transactions/" + ref + "/submissions", null, officer()));
        assertThat(submitted.get("status").asString()).isEqualTo("IN_REVIEW");
        assertThat(submitted.get("paymentRequired").asBoolean()).isFalse();
        JsonNode skip = body(getAs("/v1/transactions/" + ref + "/status-history", officer())).get("items").get(2);
        assertThat(skip.get("reason").asString()).isEqualTo("ZERO_FEE_PAYMENT_SKIPPED");
        assertThat(skip.get("previousStatus").asString()).isEqualTo("SUBMITTED");
        assertThat(skip.get("actor").get("id").asString()).isEqualTo("SYSTEM");
        MvcResult cancel = postAs("/v1/transactions/" + ref + "/cancellations", Map.of("reason", "too late"),
                officer());
        assertThat(cancel.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(cancel).get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
    }

    @Test
    @Tag("CC-027")
    @DisplayName("CC-027: no second open transaction of a service for a subject, unless the service allows it")
    void duplicates() throws Exception {
        define("ITX_DUP", "FEE", true, false);
        define("ITX_MULTI", null, true, true);
        String first = openOk("ITX_DUP", "VIN-DUP");
        MvcResult dup = open("ITX_DUP", "VIN-DUP");
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(dup).get("code").asString()).isEqualTo("DUPLICATE_OPEN_TRANSACTION");
        assertThat(body(dup).get("detail").asString()).contains(first);
        assertThat(body(dup).get("requirementRefs").toString()).contains("CC-027");
        // Pending payment is still open.
        postAs("/v1/transactions/" + first + "/submissions", null, officer());
        assertThat(open("ITX_DUP", "VIN-DUP").getResponse().getStatus()).isEqualTo(409);
        // Same number as another subject type, or another subject: not a duplicate.
        assertThat(postAs("/v1/transactions", newTransaction("ITX_DUP", "PLATE", "VIN-DUP", "R1", null), officer())
                .getResponse().getStatus()).isEqualTo(201);
        openOk("ITX_DUP", "VIN-DUP2");
        // Final again: a new one may be opened.
        postAs("/v1/transactions/" + first + "/cancellations", Map.of("reason", "wrong vehicle"), officer());
        openOk("ITX_DUP", "VIN-DUP");
        // A service that allows several.
        openOk("ITX_MULTI", "VIN-DUP");
        openOk("ITX_MULTI", "VIN-DUP");
    }

    @Test
    @Tag("CC-113")
    @Tag("CC-026")
    @Tag("CC-112")
    @DisplayName("CC-113/CC-026/CC-112: region, channel, availability rules and the home-region exception")
    void access() throws Exception {
        define("ITX_ANY", null, true, false);
        define("ITX_HOME", null, false, false);
        MvcResult elsewhere = postAs("/v1/transactions", newTransaction("ITX_ANY", "VEHICLE", "VIN-A1", "R1", "R2"),
                officer());
        assertThat(body(elsewhere).get("reference").asString()).startsWith("ITX_ANY-R2-");
        assertThat(body(elsewhere).get("homeRegion").asString()).isEqualTo("R1");

        MvcResult home = postAs("/v1/transactions", newTransaction("ITX_HOME", "VEHICLE", "VIN-A1", "R1", "R2"),
                officer());
        assertThat(home.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(home).get("code").asString()).isEqualTo("HOME_REGION_ONLY");
        assertThat(body(home).get("requirementRefs").toString()).contains("CC-113");
        assertThat(postAs("/v1/transactions", newTransaction("ITX_HOME", "VEHICLE", "VIN-A1", "R1", "R1"),
                officer()).getResponse().getStatus()).isEqualTo(201);

        MvcResult region = postAs("/v1/transactions", newTransaction("ITX_ANY", "VEHICLE", "VIN-A2", "R9", null),
                officer());
        assertThat(body(region).get("code").asString()).isEqualTo("SERVICE_NOT_AVAILABLE");
        MvcResult kiosk = postVia("/v1/transactions", newTransaction("ITX_ANY", "VEHICLE", "VIN-A2", "R1", null),
                officer(), "KIOSK");
        assertThat(body(kiosk).get("code").asString()).isEqualTo("SERVICE_NOT_AVAILABLE");
        MvcResult noChannel = postVia("/v1/transactions",
                newTransaction("ITX_ANY", "VEHICLE", "VIN-A2", "R1", null), officer(), null);
        assertThat(noChannel.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(noChannel).get("code").asString()).isEqualTo("CHANNEL_REQUIRED");
        MvcResult unknown = postAs("/v1/transactions", newTransaction("ITX_NONE", "VEHICLE", "VIN-A2", "R1", null),
                officer());
        assertThat(body(unknown).get("code").asString()).isEqualTo("UNKNOWN_SERVICE");

        // Disabled in R1 on the back office after the Draft was opened: the submission is refused (Q-049).
        String ref = openOk("ITX_ANY", "VIN-A3");
        Map<String, Object> rule = new HashMap<>();
        rule.put("region", "R1");
        rule.put("channel", "BACKOFFICE");
        rule.put("enabled", false);
        rule.put("effectiveFrom", TODAY.toString());
        approve(Map.of("changeType", "ADD_AVAILABILITY_RULE", "serviceCode", "ITX_ANY", "rule", rule,
                "reason", "counter closed"));
        MvcResult refused = postAs("/v1/transactions/" + ref + "/submissions", null, officer());
        assertThat(body(refused).get("code").asString()).isEqualTo("SERVICE_NOT_AVAILABLE");
        assertThat(body(getAs("/v1/transactions/" + ref, officer())).get("status").asString()).isEqualTo("DRAFT");
        assertThat(body(postVia("/v1/transactions/" + ref + "/submissions", null, officer(), "PORTAL"))
                .get("status").asString()).isEqualTo("IN_REVIEW");
    }

    @Test
    @Tag("CC-025")
    @Tag("CC-120")
    @DisplayName("CC-025/CC-120: an applicant's transactions are tracked page by page, whatever the channel; views audited")
    void tracking() throws Exception {
        define("ITX_TRACK", null, true, true);
        List<String> refs = new java.util.ArrayList<>();
        for (String channel : List.of("BACKOFFICE", "PORTAL", "BACKOFFICE")) {
            Map<String, Object> request = newTransaction("ITX_TRACK", "VEHICLE", "VIN-TRK", "R1", null);
            request.put("applicantFileNumber", "TF-TRACK");
            MvcResult r = postVia("/v1/transactions", request, officer(), channel);
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
            refs.add(body(r).get("reference").asString());
        }

        JsonNode first = body(getAs("/v1/transactions?applicantFileNumber=TF-TRACK&limit=2", reader()));
        assertThat(first.get("items")).extracting(n -> n.get("reference").asString())
                .containsExactly(refs.get(2), refs.get(1));
        assertThat(first.get("items").get(1).get("channel").asString()).isEqualTo("PORTAL");
        String cursor = first.get("nextCursor").asString();
        JsonNode second = body(getAs("/v1/transactions?applicantFileNumber=TF-TRACK&limit=2&cursor=" + cursor,
                reader()));
        assertThat(second.get("items")).extracting(n -> n.get("reference").asString()).containsExactly(refs.get(0));
        assertThat(second.get("nextCursor").isNull()).isTrue();

        postAs("/v1/transactions/" + refs.get(1) + "/submissions", null, officer());
        JsonNode inReview = body(getAs("/v1/transactions?applicantFileNumber=TF-TRACK&status=IN_REVIEW", reader()));
        assertThat(inReview.get("items")).extracting(n -> n.get("reference").asString()).containsExactly(refs.get(1));
        assertThat(body(getAs("/v1/transactions?applicantFileNumber=TF-NOBODY", reader())).get("items")).isEmpty();

        String audited = db.sql("select new_value::text from audit_outbox where action = 'TRANSACTION_LIST_VIEWED'"
                + " and entity_id = 'TF-TRACK' order by occurred_at limit 1").query(String.class).single();
        assertThat(audited).contains(refs.get(2), refs.get(1)).doesNotContain(refs.get(0));

        MvcResult badCursor = getAs("/v1/transactions?applicantFileNumber=TF-TRACK&cursor=junk", reader());
        assertThat(badCursor.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(badCursor).get("code").asString()).isEqualTo("CURSOR_INVALID");
        MvcResult badLimit = getAs("/v1/transactions?applicantFileNumber=TF-TRACK&limit=500", reader());
        assertThat(body(badLimit).get("code").asString()).isEqualTo("LIMIT_INVALID");
        assertThat(getAs("/v1/transactions?applicantFileNumber=tf-track", reader()).getResponse().getStatus())
                .isEqualTo(422);
        assertThat(getAs("/v1/transactions", reader()).getResponse().getStatus()).isEqualTo(400);
        assertThat(getAs("/v1/transactions?applicantFileNumber=TF-TRACK", citizen()).getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    @Tag("CC-001")
    @Tag("SEC-022")
    @Tag("CH-053")
    @DisplayName("CC-001/SEC-022/CH-053: permissions per operation, unknown references and invalid input")
    void authorisationAndValidation() throws Exception {
        define("ITX_AUTH", null, true, false);
        assertThat(mvc.perform(get("/v1/transactions/ITX_AUTH-R1-2026-0000001")).andReturn().getResponse()
                .getStatus()).isEqualTo(401);
        assertThat(open("ITX_AUTH", "VIN-Z").getResponse().getStatus()).isEqualTo(201);
        String ref = "ITX_AUTH-R1-" + YEAR + "-0000001";
        assertThat(postAs("/v1/transactions", newTransaction("ITX_AUTH", "VEHICLE", "VIN-Y", "R1", null), citizen())
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(postAs("/v1/transactions", newTransaction("ITX_AUTH", "VEHICLE", "VIN-Y", "R1", null), reader())
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(getAs("/v1/transactions/" + ref, citizen()).getResponse().getStatus()).isEqualTo(403);
        assertThat(postAs("/v1/transactions/" + ref + "/submissions", null, reader()).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(postAs("/v1/transactions/" + ref + "/cancellations", Map.of("reason", "no right"), reader())
                .getResponse().getStatus()).isEqualTo(403);

        MvcResult missing = getAs("/v1/transactions/ITX_AUTH-R1-" + YEAR + "-0000099", reader());
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(missing).get("code").asString()).isEqualTo("TRANSACTION_NOT_FOUND");
        assertThat(mvc.perform(get("/v1/transactions/not-a-reference").with(reader()).header("X-Channel",
                "BACKOFFICE")).andReturn().getResponse().getStatus()).isEqualTo(404);

        Map<String, Object> lower = newTransaction("ITX_AUTH", "VEHICLE", "vin-lower", "R1", null);
        assertThat(postAs("/v1/transactions", lower, officer()).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> badType = newTransaction("ITX_AUTH", "PERSON", "VIN-X", "R1", null);
        assertThat(postAs("/v1/transactions", badType, officer()).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> extra = newTransaction("ITX_AUTH", "VEHICLE", "VIN-X", "R1", null);
        extra.put("status", "APPROVED");
        assertThat(postAs("/v1/transactions", extra, officer()).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> noHome = newTransaction("ITX_AUTH", "VEHICLE", "VIN-X", "R1", null);
        noHome.remove("homeRegion");
        assertThat(postAs("/v1/transactions", noHome, officer()).getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs("/v1/transactions/" + ref + "/cancellations", Map.of("reason", "x"), officer())
                .getResponse().getStatus()).isEqualTo(400);

        MvcResult arabic = mvc.perform(post("/v1/transactions").with(officer()).header("Accept-Language", "ar")
                .header("Idempotency-Key", "it-arabic-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(newTransaction("ITX_AUTH", "VEHICLE", "VIN-Z", "R1", null)))
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)).andReturn();
        assertThat(body(arabic).get("code").asString()).isEqualTo("CHANNEL_REQUIRED");
        assertThat(body(arabic).get("detail").asString()).startsWith("لا يحدد الطلب القناة");
    }

    @Test
    @Tag("CC-022")
    @Tag("CC-023")
    @Tag("CC-027")
    @Tag("CC-021")
    @DisplayName("CC-021/CC-022/CC-023/CC-027: the database refuses illegal transitions, history edits, duplicates and bad references")
    void databaseGuards() throws Exception {
        define("ITX_DB", "FEE", true, false);
        define("ITX_DB0", null, true, false);
        String ref = openOk("ITX_DB", "VIN-DB");
        String where = " where reference = '" + ref + "'";

        // Lifecycle: skipping steps, editing fixed fields, a version gap, and routing that does not match the fee.
        assertThatThrownBy(() -> db.sql("update service_transaction set status = 'APPROVED', status_reason = 'APPROVED',"
                + " version = version + 1" + where).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("is not in the lifecycle");
        assertThatThrownBy(() -> db.sql("update service_transaction set subject_id = 'VIN-OTHER'" + where).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("only the status");
        assertThatThrownBy(() -> db.sql("update service_transaction set status = 'CANCELLED',"
                + " status_reason = 'CANCELLED', version = version + 2, status_note = 'gap'" + where).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("must follow the previous one");
        postAs("/v1/transactions/" + ref + "/submissions", null, officer());
        String free = openOk("ITX_DB0", "VIN-DB");
        db.sql("update service_transaction set status = 'SUBMITTED', status_reason = 'SUBMITTED',"
                + " version = version + 1, submitted_at = now() where reference = '" + free + "'").update();
        assertThatThrownBy(() -> db.sql("update service_transaction set status = 'PENDING_PAYMENT',"
                + " status_reason = 'PAYMENT_REQUIRED', version = version + 1 where reference = '" + free + "'")
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("payment routing");

        // History is append-only; nothing is deleted.
        assertThatThrownBy(() -> db.sql("update transaction_status_history set reason = 'APPROVED'").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from transaction_status_history").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from service_transaction" + where).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("update transaction_reference_sequence set last_value = last_value - 1")
                .update()).isInstanceOf(DataAccessException.class);
        assertThat(db.sql("select count(*) from transaction_status_history h join service_transaction t"
                + " on t.id = h.transaction_id" + where.replace("reference", "t.reference")).query(Long.class)
                .single()).isEqualTo(3);

        // CC-027 index and the CC-021 reference format, below the application.
        String insert = """
                insert into service_transaction (id, reference, service_code, reference_year, reference_sequence,
                    service_version, module, fee_code, payment_required, allows_concurrent_open, subject_type,
                    subject_id, applicant_file_number, home_region, region, channel, created_at, created_by, status,
                    status_reason, status_actor_id, status_channel, status_changed_at, version)
                values (gen_random_uuid(), :ref, 'ITX_DB', :year, :seq, 1, 'VEHICLE', 'FEE', true, false, 'VEHICLE',
                    :subject, 'TF1', 'R1', 'R1', 'BACKOFFICE', now(), 'x', 'DRAFT', 'CREATED', 'x', 'BACKOFFICE',
                    now(), 1)""";
        assertThatThrownBy(() -> db.sql(insert).param("ref", "ITX_DB-R1-" + YEAR + "-0000500").param("year", YEAR)
                .param("seq", 500).param("subject", "VIN-DB").update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_transaction_open_subject");
        assertThatThrownBy(() -> db.sql(insert).param("ref", "ITX_DB-R1-" + YEAR + "-500").param("year", YEAR)
                .param("seq", 500).param("subject", "VIN-NEW").update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_transaction_reference");
        assertThat(db.sql(insert).param("ref", "ITX_DB-R1-" + YEAR + "-0000501").param("year", YEAR)
                .param("seq", 501).param("subject", "VIN-NEW").update()).isEqualTo(1);
    }

    @Test
    @Tag("CC-024")
    @Tag("CC-023")
    @Tag("CC-120")
    @DisplayName("CC-024: the job expires Drafts older than the configured 30 days, as SYSTEM, skipping Drafts another instance holds")
    void draftExpiry() throws Exception {
        define("ITX_EXP", null, true, false);
        String old = backdatedDraft("ITX_EXP", 900_001, "VIN-EXP1", 31);
        String held = backdatedDraft("ITX_EXP", 900_002, "VIN-EXP2", 31);
        String recent = backdatedDraft("ITX_EXP", 900_003, "VIN-EXP3", 29);
        String fresh = openOk("ITX_EXP", "VIN-EXP4");

        // Another instance (or a submission) holds one Draft: this run skips it instead of waiting.
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (var lock = other.prepareStatement("select id from service_transaction where reference = ? for update")) {
                lock.setString(1, held);
                lock.executeQuery();
            }
            assertThat(draftExpiryJob.run()).isEqualTo(1);
            other.rollback();
        }
        assertThat(status(old)).isEqualTo("EXPIRED");
        assertThat(status(held)).isEqualTo("DRAFT");
        assertThat(draftExpiryJob.run()).isEqualTo(1);
        assertThat(status(held)).isEqualTo("EXPIRED");
        assertThat(status(recent)).isEqualTo("DRAFT");
        assertThat(status(fresh)).isEqualTo("DRAFT");
        assertThat(draftExpiryJob.run()).isZero();

        // History through the API (contract-validated): actor and channel SYSTEM, reason EXPIRED, period noted.
        JsonNode last = body(getAs("/v1/transactions/" + old + "/status-history", officer())).get("items").get(1);
        assertThat(last.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(last.get("previousStatus").asString()).isEqualTo("DRAFT");
        assertThat(last.get("reason").asString()).isEqualTo("EXPIRED");
        assertThat(last.get("actor").get("id").asString()).isEqualTo("SYSTEM");
        assertThat(last.get("channel").asString()).isEqualTo("SYSTEM");
        assertThat(last.get("note").asString()).isEqualTo("not submitted within PT720H");
        assertThat(db.sql("select actor_id || '/' || channel || '/' || reason from audit_outbox"
                + " where action = 'TRANSACTION_EXPIRED' and entity_id = :ref").param("ref", old)
                .query(String.class).single()).isEqualTo("SYSTEM/SYSTEM/not submitted within PT720H");

        // Too late to submit; the subject is free again (CC-027).
        MvcResult late = postAs("/v1/transactions/" + old + "/submissions", null, officer());
        assertThat(late.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(late).get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
        openOk("ITX_EXP", "VIN-EXP1");

        // SYSTEM is not a channel a request may use.
        MvcResult viaSystem = postVia("/v1/transactions", newTransaction("ITX_EXP", "VEHICLE", "VIN-EXP5", "R1", null),
                officer(), "SYSTEM");
        assertThat(viaSystem.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(viaSystem).get("code").asString()).isEqualTo("CHANNEL_REQUIRED");

        // The database: only the system expires a Draft, only the system uses SYSTEM, nothing is opened through it.
        String where = " where reference = '" + recent + "'";
        assertThatThrownBy(() -> db.sql("update service_transaction set status = 'EXPIRED', status_reason = 'EXPIRED',"
                + " status_channel = 'BACKOFFICE', version = version + 1" + where).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_transaction_draft_expiry");
        assertThatThrownBy(() -> db.sql("update service_transaction set status = 'CANCELLED',"
                + " status_reason = 'CANCELLED', status_note = 'by hand', status_channel = 'SYSTEM',"
                + " version = version + 1" + where).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_transaction_channels");
        assertThatThrownBy(() -> backdatedDraft("ITX_EXP", 900_004, "VIN-EXP6", 1, "SYSTEM"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_transaction_channels");
    }

    @Test
    @Tag("FN-004")
    @Tag("CC-027")
    @DisplayName("ADR-032 (FN-004 pattern): a retry with the same Idempotency-Key replays the response instead of acting twice")
    void idempotencyKey() throws Exception {
        define("ITX_IDEM", "FEE", true, false);
        Map<String, Object> open = newTransaction("ITX_IDEM", "VEHICLE", "VIN-IDEM", "R1", null);
        String openKey = "it-idem-open-" + UUID.randomUUID();

        // Create, then a retry (lost response): same reference, Location and body; one transaction, one audit record.
        MvcResult first = postWithKey("/v1/transactions", open, officer(), openKey);
        assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isNull();
        String ref = body(first).get("reference").asString();
        MvcResult retry = postWithKey("/v1/transactions", open, officer(), openKey);
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getResponse().getHeader("Location")).isEqualTo("/v1/transactions/" + ref);
        assertThat(body(retry)).isEqualTo(body(first));
        assertThat(db.sql("select count(*) from service_transaction where service_code = 'ITX_IDEM'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from audit_outbox where action = 'TRANSACTION_CREATED' and entity_id = :ref")
                .param("ref", ref).query(Long.class).single()).isEqualTo(1);

        // What is stored: the caller, a SHA-256 of the request, the 2xx response, kept for the configured 24 hours.
        Map<String, Object> row = db.sql("select principal_id, request_hash, response_status,"
                        + " extract(epoch from expires_at - locked_at) as kept from idempotency_key"
                        + " where idempotency_key = :k")
                .param("k", openKey).query().singleRow();
        assertThat(row.get("principal_id")).isEqualTo("u-officer");
        assertThat((String) row.get("request_hash")).hasSize(64);
        assertThat(row.get("response_status")).isEqualTo(201);
        assertThat(((Number) row.get("kept")).longValue()).isEqualTo(24 * 3600);

        // The same key for another request is refused; another caller's same key is theirs, not a replay (CC-027
        // then refuses the duplicate).
        Map<String, Object> other = newTransaction("ITX_IDEM", "VEHICLE", "VIN-IDEM2", "R1", null);
        MvcResult reused = postWithKey("/v1/transactions", other, officer(), openKey);
        assertThat(reused.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(reused).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        MvcResult otherCaller = postWithKey("/v1/transactions", open,
                user("u-officer2", "officer2", "transaction.create"), openKey);
        assertThat(otherCaller.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(otherCaller).get("code").asString()).isEqualTo("DUPLICATE_OPEN_TRANSACTION");

        // Submission retried: replayed, not an ILLEGAL_TRANSITION, and the history has the steps once.
        String submitKey = "it-idem-submit-" + UUID.randomUUID();
        MvcResult submitted = postWithKey("/v1/transactions/" + ref + "/submissions", null, officer(), submitKey);
        assertThat(body(submitted).get("status").asString()).isEqualTo("PENDING_PAYMENT");
        MvcResult resubmitted = postWithKey("/v1/transactions/" + ref + "/submissions", null, officer(), submitKey);
        assertThat(resubmitted.getResponse().getStatus()).isEqualTo(200);
        assertThat(resubmitted.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(resubmitted)).isEqualTo(body(submitted));
        assertThat(db.sql("select count(*) from transaction_status_history h join service_transaction t"
                + " on t.id = h.transaction_id where t.reference = :ref").param("ref", ref).query(Long.class).single())
                .isEqualTo(3);

        // A refused request keeps nothing, so the key may be retried; a missing or malformed key is refused first.
        String failedKey = "it-idem-failed-" + UUID.randomUUID();
        MvcResult notFound = postWithKey("/v1/transactions/ITX_IDEM-R1-" + YEAR + "-0999999/submissions", null,
                officer(), failedKey);
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(db.sql("select count(*) from idempotency_key where idempotency_key = :k").param("k", failedKey)
                .query(Long.class).single()).isZero();
        MvcResult missing = postWithKey("/v1/transactions/" + ref + "/cancellations", Map.of("reason", "no key"),
                officer(), null);
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(missing).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        MvcResult malformed = postWithKey("/v1/transactions/" + ref + "/cancellations", Map.of("reason", "bad key"),
                officer(), "short");
        assertThat(body(malformed).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_INVALID");
        MvcResult arabic = mvc.perform(post("/v1/transactions/" + ref + "/cancellations").with(officer())
                .header("Accept-Language", "ar").header("X-Channel", "BACKOFFICE")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"no key\"}")).andReturn();
        assertThat(body(arabic).get("detail").asString()).startsWith("أرسل الترويسة Idempotency-Key");
        assertThat(status(ref)).isEqualTo("PENDING_PAYMENT");

        // After the retention the key is cleaned up and forgotten: the retry acts again and CC-027 refuses it.
        db.sql("update idempotency_key set expires_at = now() - interval '1 second',"
                + " locked_at = now() - interval '1 day' where idempotency_key = :k").param("k", openKey).update();
        assertThat(idempotencyCleanupJob.run()).isGreaterThanOrEqualTo(1);
        MvcResult afterRetention = postWithKey("/v1/transactions", open, officer(), openKey);
        assertThat(afterRetention.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(afterRetention).get("code").asString()).isEqualTo("DUPLICATE_OPEN_TRANSACTION");

        // The database keeps only well-formed keys and only successful responses.
        assertThatThrownBy(() -> db.sql("insert into idempotency_key (principal_id, idempotency_key, request_hash,"
                + " lock_token, locked_at, expires_at, response_status, completed_at) values ('u', 'it-db-check-1',"
                + " repeat('a', 64), 't', now(), now() + interval '1 hour', 500, now())").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_idempotency_response");
        assertThatThrownBy(() -> db.sql("insert into idempotency_key (principal_id, idempotency_key, request_hash,"
                + " lock_token, locked_at, expires_at) values ('u', 'bad key', repeat('a', 64), 't', now(),"
                + " now() + interval '1 hour')").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_idempotency_key_format");
    }

    private String backdatedDraft(String serviceCode, int sequence, String subjectId, int daysAgo) {
        return backdatedDraft(serviceCode, sequence, subjectId, daysAgo, "BACKOFFICE");
    }

    /** A Draft opened {@code daysAgo} days ago, written below the application (created_at is fixed once stored). */
    private String backdatedDraft(String serviceCode, int sequence, String subjectId, int daysAgo, String channel) {
        String ref = serviceCode + "-R1-" + YEAR + "-" + String.format("%07d", sequence);
        db.sql("""
                        insert into service_transaction (id, reference, service_code, reference_year,
                            reference_sequence, service_version, module, fee_code, payment_required,
                            allows_concurrent_open, subject_type, subject_id, applicant_file_number, home_region,
                            region, channel, created_at, created_by, status, status_reason, status_actor_id,
                            status_channel, status_changed_at, version)
                        values (gen_random_uuid(), :ref, :code, :year, :seq, 1, 'VEHICLE', null, false, false,
                            'VEHICLE', :subject, 'TF-1001', 'R1', 'R1', :channel,
                            now() - make_interval(days => :days), 'u-officer', 'DRAFT', 'CREATED', 'u-officer',
                            :channel, now() - make_interval(days => :days), 1)""")
                .param("ref", ref).param("code", serviceCode).param("year", YEAR).param("seq", sequence)
                .param("subject", subjectId).param("channel", channel).param("days", daysAgo).update();
        return ref;
    }

    private String status(String reference) {
        return db.sql("select status from service_transaction where reference = :ref").param("ref", reference)
                .query(String.class).single();
    }

    @Test
    @Tag("CC-032")
    @Tag("CC-022")
    @DisplayName("CC-032: holds block submission with every reason and next step in the caller's language; the "
            + "transaction stays a Draft; no answer means no submission (503)")
    void eligibilityAtSubmission() throws Exception {
        define("ITX_ELIG", "FEE", true, false);
        String ref = openOk("ITX_ELIG", "BLOCKED-1");
        ELIGIBILITY.received.clear();

        MvcResult blocked = call(post("/v1/transactions/" + ref + "/submissions").header("Accept-Language", "en"),
                Request.Method.POST, "/v1/transactions/" + ref + "/submissions", null, officer(), "PORTAL");
        assertThat(blocked.getResponse().getStatus()).as(blocked.getResponse().getContentAsString()).isEqualTo(422);
        JsonNode problem = body(blocked);
        assertThat(problem.get("code").asString()).isEqualTo("ELIGIBILITY_BLOCKED");
        assertThat(problem.get("detail").asString()).isEqualTo("There are unpaid fines. Pay them, then try again. "
                + "A restriction applies. Contact the Authority.");
        assertThat(problem.get("nextStep").asString()).isEqualTo("PAY_FINES");
        assertThat(problem.get("blocks")).hasSize(2);
        assertThat(problem.get("blocks").get(1).get("reasonCode").asString()).isEqualTo("RESTRICTED");
        assertThat(problem.get("blocks").get(1).get("nextStep").asString()).isEqualTo("CONTACT_AUTHORITY");
        assertThat(problem.get("requirementRefs").toString()).contains("CC-032");

        // Asked with transaction-service's own token, the request's channel and language, for the subject and the
        // applicant's traffic file.
        EligibilityStub.Received asked = ELIGIBILITY.received.getFirst();
        assertThat(asked.authorisation()).isEqualTo("Bearer " + EligibilityStub.TOKEN);
        assertThat(asked.channel()).isEqualTo("PORTAL");
        assertThat(asked.language()).isEqualTo("en");
        assertThat(asked.correlationId()).isEqualTo("txn-it-transactions");
        assertThat(asked.body().get("serviceCode").asString()).isEqualTo("ITX_ELIG");
        assertThat(asked.body().get("subjects")).hasSize(2);
        assertThat(asked.body().get("subjects").get(0).get("type").asString()).isEqualTo("VEHICLE");
        assertThat(asked.body().get("subjects").get(0).get("id").asString()).isEqualTo("BLOCKED-1");
        assertThat(asked.body().get("subjects").get(1).get("type").asString()).isEqualTo("TRAFFIC_FILE");
        assertThat(asked.body().get("subjects").get(1).get("id").asString()).isEqualTo("TF-1001");

        MvcResult arabic = call(post("/v1/transactions/" + ref + "/submissions").header("Accept-Language", "ar"),
                Request.Method.POST, "/v1/transactions/" + ref + "/submissions", null, officer(), "BACKOFFICE");
        assertThat(body(arabic).get("detail").asString()).isEqualTo("توجد مخالفات غير مدفوعة. تواصل مع الهيئة.");

        JsonNode draft = body(getAs("/v1/transactions/" + ref, reader()));
        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.get("version").asLong()).isEqualTo(1);
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :ref and action = 'TRANSACTION_SUBMITTED'")
                .param("ref", ref).query(Long.class).single()).isZero();
        assertThat(db.sql("select count(*) from transaction_status_history h join service_transaction t"
                + " on t.id = h.transaction_id where t.reference = :ref").param("ref", ref).query(Long.class).single())
                .isEqualTo(1);

        // hold-eligibility-service cannot answer: refused, still a Draft.
        String down = openOk("ITX_ELIG", "DOWN-1");
        MvcResult unavailable = postAs("/v1/transactions/" + down + "/submissions", null, officer());
        assertThat(unavailable.getResponse().getStatus()).isEqualTo(503);
        assertThat(body(unavailable).get("code").asString()).isEqualTo("ELIGIBILITY_UNAVAILABLE");
        assertThat(body(getAs("/v1/transactions/" + down, reader())).get("status").asString()).isEqualTo("DRAFT");

        // Eligible: submitted as before; a refused submission keeps nothing under its Idempotency-Key.
        String clear = openOk("ITX_ELIG", "VIN-ELIG-OK");
        assertThat(body(postAs("/v1/transactions/" + clear + "/submissions", null, officer())).get("status")
                .asString()).isEqualTo("PENDING_PAYMENT");
    }
}

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

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final int YEAR = TODAY.getYear();
    private static OpenApiInteractionValidator contract;

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

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who, String channel) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        builder.with(who).header("X-Correlation-Id", "txn-it-transactions")
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        if (channel != null) {
            builder.header("X-Channel", channel);
        }
        if (payload != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(payload);
        }
        MvcResult result = mvc.perform(builder).andReturn();

        SimpleRequest.Builder req = new SimpleRequest.Builder(method, path).withHeader("Authorization", "Bearer t");
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
                : contract.validateResponse(path, method, response);
        assertThat(report.getMessages()).as("OpenAPI contract for %s %s -> %d", method, path,
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
}

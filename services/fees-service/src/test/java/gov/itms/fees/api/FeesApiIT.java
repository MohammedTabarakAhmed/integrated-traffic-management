package gov.itms.fees.api;

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
 * End-to-end slice test of fees: HTTP → security → service → PostgreSQL (V1 guards) → audit outbox, with every request
 * and response validated against fees-service.v1.yaml. Tests share one database, so each uses its own service code.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("CC-040")
class FeesApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final AtomicInteger SERVICES = new AtomicInteger();
    private static OpenApiInteractionValidator contract;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/fees-service.v1.yaml").build();
    }

    private static RequestPostProcessor maker() {
        return user("u-maker", "maker", "FEE_MAKER", "fees.version.submit", "fees.version.read");
    }

    private static RequestPostProcessor checker() {
        return user("u-checker", "checker", "FEE_CHECKER", "fees.version.approve", "fees.version.read");
    }

    private static RequestPostProcessor customer() {
        return user("u-customer", "customer", "CUSTOMER");
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
        boolean idempotent = path.startsWith("/v1/fee-versions") || path.startsWith("/v1/fee-adjustments");
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

    private static String newService() {
        return "SV" + SERVICES.incrementAndGet() + "_" + Long.toString(System.nanoTime() % 1_000_000, 36).toUpperCase();
    }

    private static Map<String, Object> version(String service, String fee, Map<String, Object> method,
                                               LocalDate from, String region) {
        Map<String, Object> m = new HashMap<>();
        m.put("feeCode", fee + "_" + service);
        m.put("serviceCode", service);
        m.put("nameNl", "رسم");
        m.put("nameEn", "Fee " + fee);
        m.put("region", region);
        m.put("financialItem", "REV_" + fee);
        m.put("beneficiary", "AUTHORITY");
        m.put("method", method);
        m.put("effectiveFrom", from.toString());
        m.put("reason", "new fee for the service");
        return m;
    }

    private String approvedVersion(Map<String, Object> request) throws Exception {
        MvcResult submitted = postAs("/v1/fee-versions", request, maker());
        assertThat(submitted.getResponse().getStatus()).as(submitted.getResponse().getContentAsString())
                .isEqualTo(201);
        String id = body(submitted).get("id").asString();
        MvcResult approved = postAs("/v1/fee-versions/" + id + "/approvals", Map.of(), checker());
        assertThat(approved.getResponse().getStatus()).as(approved.getResponse().getContentAsString())
                .isEqualTo(200);
        return id;
    }

    @Test
    @Tag("CC-046")
    @DisplayName("maker submits, the maker cannot approve, a checker approves; audited; stored decision is final")
    void makerChecker() throws Exception {
        String service = newService();
        MvcResult submitted = postAs("/v1/fee-versions",
                version(service, "BASE", Map.of("type", "FIXED", "amount", 15000), TODAY, null), maker());
        String id = body(submitted).get("id").asString();
        assertThat(body(submitted).get("status").asString()).isEqualTo("PENDING");
        assertThat(body(submitted).get("versionNo").asInt()).isEqualTo(1);

        MvcResult selfApproval = postAs("/v1/fee-versions/" + id + "/approvals", Map.of(),
                user("u-maker", "maker", "BOTH", "fees.version.approve"));
        assertThat(selfApproval.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(selfApproval)).isEqualTo("SAME_MAKER_CHECKER");
        assertThat(postAs("/v1/fee-versions/" + id + "/approvals", Map.of(), maker()).getResponse().getStatus())
                .isEqualTo(403);

        MvcResult approved = postAs("/v1/fee-versions/" + id + "/approvals", Map.of("reason", "checked"), checker());
        assertThat(body(approved).get("status").asString()).isEqualTo("APPROVED");
        assertThat(code(postAs("/v1/fee-versions/" + id + "/rejections", Map.of("reason", "too late"), checker())))
                .isEqualTo("FEE_VERSION_DECIDED");
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :id and action in "
                + "('FEE_VERSION_SUBMITTED', 'FEE_VERSION_APPROVED')").param("id", id).query(Long.class).single())
                .isEqualTo(2);
        assertThatThrownBy(() -> db.sql("update fee_version set name_en = 'x' where id = cast(:id as uuid)")
                .param("id", id).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.sql("delete from fee_version where id = cast(:id as uuid)").param("id", id)
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("never deleted");
    }

    @Test
    @Tag("CC-047")
    @Tag("CC-042")
    @DisplayName("a quote is itemised, most specific version per fee, frozen against later fee changes")
    void quote() throws Exception {
        String service = newService();
        approvedVersion(version(service, "BASE", Map.of("type", "FIXED", "amount", 10000), TODAY, null));
        approvedVersion(version(service, "BASE", Map.of("type", "FIXED", "amount", 12000), TODAY, "DXB"));
        approvedVersion(version(service, "SEATS", Map.of("type", "PER_UNIT", "unit", "SEAT", "rate", "2500"),
                TODAY, null));

        Map<String, Object> request = new HashMap<>();
        request.put("serviceCode", service);
        request.put("region", "DXB");
        request.put("customerCategory", "CITIZEN");
        request.put("quantities", Map.of("SEAT", "7"));
        MvcResult quoted = postAs("/v1/quotes", request, customer());
        assertThat(quoted.getResponse().getStatus()).as(quoted.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode quote = body(quoted);
        assertThat(quote.get("lines")).hasSize(2);
        assertThat(quote.get("lines").get(0).get("feeCode").asString()).isEqualTo("BASE_" + service);
        assertThat(quote.get("lines").get(0).get("amount").asLong()).isEqualTo(12000);
        assertThat(quote.get("lines").get(1).get("amount").asLong()).isEqualTo(17500);
        // CC-042: every line names its financial item (revenue account) and beneficiary entity.
        for (JsonNode line : quote.get("lines")) {
            assertThat(line.get("financialItem").asString()).isEqualTo("REV_" + line.get("feeCode").asString()
                    .substring(0, line.get("feeCode").asString().indexOf('_')));
            assertThat(line.get("beneficiary").asString()).isEqualTo("AUTHORITY");
        }
        assertThat(quote.get("total").asLong()).isEqualTo(29500);
        assertThat(quote.get("currency").asString()).isEqualTo("XTS");
        String quoteId = quote.get("id").asString();

        // A change from tomorrow does not touch the quote, nor today's fee.
        approvedVersion(version(service, "BASE", Map.of("type", "FIXED", "amount", 99999), TODAY.plusDays(1), "DXB"));
        assertThat(body(getAs("/v1/quotes/" + quoteId, customer())).get("total").asLong()).isEqualTo(29500);
        assertThat(body(postAs("/v1/quotes", request, customer())).get("total").asLong()).isEqualTo(29500);
        request.put("date", TODAY.plusDays(1).toString());
        assertThat(body(postAs("/v1/quotes", request, customer())).get("total").asLong()).isEqualTo(99999 + 17500);

        request.put("quantities", Map.of());
        assertThat(code(postAs("/v1/quotes", request, customer()))).isEqualTo("QUANTITY_REQUIRED");
        request.put("date", TODAY.minusDays(1).toString());
        assertThat(code(postAs("/v1/quotes", request, customer()))).isEqualTo("QUOTE_DATE_IN_PAST");
        assertThatThrownBy(() -> db.sql("update fee_quote set total = 1 where id = cast(:id as uuid)")
                .param("id", quoteId).update()).isInstanceOf(DataAccessException.class).hasMessageContaining("frozen");
    }

    @Test
    @Tag("CC-046")
    @DisplayName("approving a successor ends the previous version; a later-starting approved version is an overlap")
    void successorsAndOverlap() throws Exception {
        String service = newService();
        String first = approvedVersion(version(service, "BASE", Map.of("type", "FIXED", "amount", 100),
                TODAY.plusDays(1), null));
        approvedVersion(version(service, "BASE", Map.of("type", "FIXED", "amount", 200), TODAY.plusDays(30), null));
        JsonNode ended = body(getAs("/v1/fee-versions/" + first, maker()));
        assertThat(ended.get("effectiveTo").asString()).isEqualTo(TODAY.plusDays(29).toString());

        MvcResult earlier = postAs("/v1/fee-versions",
                version(service, "BASE", Map.of("type", "FIXED", "amount", 300), TODAY.plusDays(10), null), maker());
        String earlierId = body(earlier).get("id").asString();
        MvcResult overlap = postAs("/v1/fee-versions/" + earlierId + "/approvals", Map.of(), checker());
        assertThat(code(overlap)).isEqualTo("FEE_VERSION_OVERLAP");

        JsonNode list = body(getAs("/v1/fee-versions?feeCode=BASE_" + service, maker()));
        assertThat(list.get("items")).hasSize(3);
        Map<String, Object> otherService = version(newService(), "BASE", Map.of("type", "FIXED", "amount", 1), TODAY,
                null);
        otherService.put("feeCode", "BASE_" + service);
        MvcResult other = postAs("/v1/fee-versions", otherService, maker());
        assertThat(code(other)).as("BASE now belongs to another service").isEqualTo("FEE_SERVICE_MISMATCH");
    }

    @Test
    @Tag("CC-041")
    @DisplayName("tiered and formula methods round-trip through the API; bad formulas are refused")
    void methods() throws Exception {
        String service = newService();
        String fee = "TONS";
        approvedVersion(version(service, fee, Map.of("type", "TIERED", "unit", "TONNE", "mode", "GRADUATED",
                "tiers", List.of(Map.of("upTo", "3.5", "rate", "1000"), new HashMap<>(Map.of("rate", "2000")))),
                TODAY, null));
        String formulaFee = "FORM";
        approvedVersion(version(service, formulaFee,
                Map.of("type", "FORMULA", "expression", "max(5000, tonne * 1500)"), TODAY, null));
        Map<String, Object> request = Map.of("serviceCode", service, "region", "DXB", "customerCategory", "COMPANY",
                "quantities", Map.of("TONNE", "5"));
        JsonNode quote = body(postAs("/v1/quotes", request, customer()));
        assertThat(quote.get("total").asLong()).as("3.5*1000 + 1.5*2000 + max(5000, 7500)").isEqualTo(14000);

        MvcResult bad = postAs("/v1/fee-versions", version(service, "BAD",
                Map.of("type", "FORMULA", "expression", "System.exit(0)"), TODAY, null), maker());
        assertThat(code(bad)).isEqualTo("FORMULA_INVALID");
        MvcResult past = postAs("/v1/fee-versions", version(service, "PAST",
                Map.of("type", "FIXED", "amount", 1), TODAY.minusDays(1), null), maker());
        assertThat(code(past)).isEqualTo("FEE_START_IN_PAST");
    }

    @Test
    @Tag("CC-043")
    @Tag("CC-044")
    @Tag("CC-045")
    @DisplayName("a quote applies an approved campaign discount, a late fee from the expiry date and VAT")
    void adjustmentsAndLateFees() throws Exception {
        String service = newService();
        approvedVersion(version(service, "BASE", Map.of("type", "FIXED", "amount", 10000), TODAY, null));
        approvedVersion(version(service, "LATE", Map.of("type", "LATE", "period", "MONTH", "amount", 1000,
                "graceDays", 0), TODAY, null));
        Map<String, Object> discount = new HashMap<>();
        discount.put("code", "SUMMER");
        discount.put("kind", "DISCOUNT");
        discount.put("serviceCode", service);
        discount.put("nameNl", "خصم");
        discount.put("nameEn", "Summer campaign");
        discount.put("feeCodes", List.of("BASE_" + service));
        discount.put("campaignCode", "SUMMER26");
        discount.put("percent", "20");
        discount.put("effectiveFrom", TODAY.toString());
        discount.put("reason", "summer campaign");
        Map<String, Object> vat = new HashMap<>(Map.of("code", "VAT", "kind", "LEVY", "serviceCode", service,
                "nameNl", "ضريبة", "nameEn", "VAT", "percent", "5", "financialItem", "VAT_PAYABLE",
                "beneficiary", "TAX_AUTHORITY", "effectiveFrom", TODAY.toString(), "reason", "value added tax"));
        for (Map<String, Object> adjustment : List.of(discount, vat)) {
            MvcResult submitted = postAs("/v1/fee-adjustments", adjustment, maker());
            assertThat(submitted.getResponse().getStatus()).as(submitted.getResponse().getContentAsString())
                    .isEqualTo(201);
            String id = body(submitted).get("id").asString();
            assertThat(postAs("/v1/fee-adjustments/" + id + "/approvals", Map.of(), checker()).getResponse()
                    .getStatus()).isEqualTo(200);
        }
        assertThat(code(postAs("/v1/fee-adjustments", discount, maker()))).isEqualTo("ADJUSTMENT_CODE_TAKEN");

        Map<String, Object> request = new HashMap<>(Map.of("serviceCode", service, "region", "DXB",
                "customerCategory", "CITIZEN", "campaignCode", "SUMMER26",
                "expiryDate", TODAY.minusMonths(2).minusDays(1).toString()));
        JsonNode quote = body(postAs("/v1/quotes", request, customer()));
        JsonNode lines = quote.get("lines");
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).get("discount").asLong()).isEqualTo(2000);
        assertThat(lines.get(0).get("adjustmentCode").asString()).isEqualTo("SUMMER");
        assertThat(lines.get(1).get("amount").asLong()).as("three started months late").isEqualTo(3000);
        assertThat(lines.get(2).get("kind").asString()).isEqualTo("LEVY");
        assertThat(lines.get(2).get("amount").asLong()).as("5% of 8000 + 3000").isEqualTo(550);
        assertThat(quote.get("total").asLong()).isEqualTo(8000 + 3000 + 550);

        request.remove("expiryDate");
        assertThat(code(postAs("/v1/quotes", request, customer()))).isEqualTo("EXPIRY_DATE_REQUIRED");
    }
}

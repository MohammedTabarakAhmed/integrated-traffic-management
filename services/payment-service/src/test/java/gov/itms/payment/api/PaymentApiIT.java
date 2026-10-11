package gov.itms.payment.api;

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
import com.sun.net.httpserver.HttpServer;
import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.AuthorisationResolver;
import gov.itms.platform.security.Scopes;
import gov.itms.payment.infrastructure.HmacCallbackVerifierTest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
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
 * End-to-end slice test of payments: HTTP → security → service → PostgreSQL (V1 guards, gapless sequences) → outboxes,
 * with an HTTP stand-in for the gateway's session API (ICD-03). Every request and response is validated against
 * payment-service.v1.yaml. Each test uses its own region, so receipt sequences do not mix. The pending job is off.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.payments.pending-job.enabled=false",
        "itms.payments.gateway.callback-keys=" + PaymentApiIT.CALLBACK_KEY,
        "itms.payments.gateway.callback-base-url=http://itms.test"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("FN-002")
class PaymentApiIT {

    static final String CALLBACK_KEY = "it-callback-key";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final HttpServer GATEWAY = gateway();
    private static final AtomicInteger REGIONS = new AtomicInteger();
    private static final Set<String> TILLS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int YEAR = LocalDate.now(ZoneOffset.UTC).getYear();
    private static OpenApiInteractionValidator contract;

    private static HttpServer gateway() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            s.createContext("/v1/payment-sessions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String sid = "sess-" + UUID.randomUUID();
                byte[] body = ("{\"sessionId\":\"" + sid + "\",\"redirectUrl\":\"https://gw.test/pay/" + sid + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            s.createContext("/v1/refunds", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = ("{\"refundRef\":\"rf-" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void gatewayUrl(DynamicPropertyRegistry registry) {
        registry.add("itms.payments.gateway.base-url", () -> "http://127.0.0.1:" + GATEWAY.getAddress().getPort());
    }

    /**
     * Grants from the token as in token-claims mode (national scope), plus a REFUND_AMOUNT approval limit in XTS from
     * the {@code refundLimit} claim (CC-003), which token-claims mode cannot express.
     */
    @TestConfiguration
    static class ApprovalLimits {
        @Bean
        AuthorisationResolver authorisationResolver() {
            return jwt -> {
                Set<String> permissions = new HashSet<>(Objects.requireNonNullElse(
                        jwt.getClaimAsStringList("permissions"), List.<String>of()));
                if (permissions.isEmpty()) {
                    return new Authorisation(jwt.getSubject(), List.of(), jwt.getIssuedAt());
                }
                Object limit = jwt.getClaims().get("refundLimit");
                List<Authorisation.ApprovalLimit> limits = limit == null ? List.of()
                        : List.of(new Authorisation.ApprovalLimit("REFUND_AMOUNT", ((Number) limit).longValue(), "XTS"));
                String role = Objects.requireNonNullElse(jwt.getClaimAsStringList("roles"), List.of("TEST")).getFirst();
                return new Authorisation(jwt.getSubject(), List.of(new Authorisation.Grant(role, permissions,
                        Scopes.NATIONAL, jwt.getExpiresAt(), limits)), jwt.getIssuedAt());
            };
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/payment-service.v1.yaml").build();
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.stop(0);
    }

    private static RequestPostProcessor registrar() {
        return user("svc-violation", "PAYABLE_REGISTRAR", "payment.payable.register");
    }

    private static RequestPostProcessor cashier() {
        return user("u-cashier", "CASHIER", "payment.collect", "payment.receipt.read", "payment.till.manage");
    }

    private static RequestPostProcessor supervisor() {
        return user("u-super", "CASHIER_SUPERVISOR", "payment.receipt.cancel", "payment.receipt.read",
                "payment.exception.read");
    }

    private static RequestPostProcessor gatewayAdapter() {
        return user("svc-gateway", "PAYMENT_GATEWAY_ADAPTER", "payment.gateway.callback");
    }

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("roles", List.of("CUSTOMER")));
    }

    private static RequestPostProcessor user(String sub, String role, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub)
                .claim("roles", List.of(role))
                .claim("permissions", Arrays.asList(permissions)));
    }

    private MvcResult call(MockHttpServletRequestBuilder builder, Request.Method method, String path, Object body,
                           RequestPostProcessor who, String key, String channel) throws Exception {
        String payload = body == null ? null : json.writeValueAsString(body);
        builder.with(who).header("X-Channel", channel)
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

    private MvcResult postAs(String path, Object body, RequestPostProcessor who, String channel) throws Exception {
        boolean idempotent = !path.endsWith("/callbacks");
        return call(post(path), Request.Method.POST, path, body, who, idempotent ? "it-" + UUID.randomUUID() : null,
                channel);
    }

    private MvcResult postAs(String path, Object body, RequestPostProcessor who) throws Exception {
        return postAs(path, body, who, "BACKOFFICE");
    }

    private MvcResult getAs(String path, RequestPostProcessor who) throws Exception {
        return call(get(path), Request.Method.GET, path, null, who, null, "BACKOFFICE");
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String code(MvcResult r) throws Exception {
        return body(r).get("code").asString();
    }

    private static String newRegion() {
        return "R" + REGIONS.incrementAndGet() + "_" + Long.toString(System.nanoTime() % 1_000_000, 36).toUpperCase();
    }

    /** FN-020: counter payments need the cashier's open till in the region; one is opened per test region. */
    private void tillFor(String region) throws Exception {
        if (TILLS.add(region)) {
            MvcResult r = postAs("/v1/tills", Map.of("region", region, "openingAmountMinor", 0), cashier());
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        }
    }

    private String payable(String region, String ref, long amount) throws Exception {
        tillFor(region);
        MvcResult r = postAs("/v1/payables", Map.of("itemType", "VIOLATION", "itemRef", ref, "trafficFileNumber",
                "TF-1", "region", region, "currency", "XTS", "lines", List.of(Map.of("financialItem", "FINE_REVENUE",
                        "beneficiary", "AUTHORITY", "amountMinor", amount))), registrar());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("id").asString();
    }

    private String basket(List<String> payables, RequestPostProcessor who) throws Exception {
        MvcResult r = postAs("/v1/baskets", Map.of("payableIds", payables), who, "PORTAL");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("id").asString();
    }

    private MvcResult callback(String paymentId, String txn, String status, long amount, String signatureKey)
            throws Exception {
        String paidAt = "2026-10-06T08:00:00Z";
        Map<String, Object> body = new HashMap<>(Map.of("gatewayTxnId", txn, "merchantRef", paymentId, "status",
                status, "amountMinor", amount, "currency", "XTS", "paidAt", paidAt, "signature",
                HmacCallbackVerifierTest.sign(signatureKey, UUID.fromString(paymentId), txn, status, amount, "XTS",
                        paidAt)));
        return postAs("/v1/payments/" + paymentId + "/callbacks", body, gatewayAdapter(), "PARTNER");
    }

    @Test
    @Tag("UAT-4")
    @Tag("FN-004")
    @DisplayName("UAT-4: two identical gateway callbacks give exactly one receipt; the second is logged DUPLICATE")
    void uat4DuplicateCallback() throws Exception {
        String region = newRegion();
        String fine = payable(region, "VF-" + region.replace('_', '-'), 50000);
        String basket = basket(List.of(fine), customer("c-uat4"));
        MvcResult started = postAs("/v1/baskets/" + basket + "/payments", Map.of("method", "GATEWAY_CARD",
                "returnUrl", "https://portal.test/return"), customer("c-uat4"), "PORTAL");
        assertThat(started.getResponse().getStatus()).as(started.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(started).get("redirectUrl").asString()).startsWith("https://gw.test/pay/");
        String payment = body(started).get("payment").get("id").asString();
        assertThat(body(started).get("payment").get("status").asString()).isEqualTo("PENDING");

        JsonNode first = body(callback(payment, "gw-uat4", "SUCCESS", 50000, CALLBACK_KEY));
        JsonNode second = body(callback(payment, "gw-uat4", "SUCCESS", 50000, CALLBACK_KEY));
        assertThat(first.get("duplicate").asBoolean()).isFalse();
        assertThat(first.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(second.get("duplicate").asBoolean()).isTrue();
        assertThat(second.get("receiptNumber").asString()).isEqualTo(first.get("receiptNumber").asString());

        assertThat(db.sql("select count(*) from receipt where payment_id = cast(:p as uuid)").param("p", payment)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("select outcome from payment_callback_log where payment_id = cast(:p as uuid) order by id")
                .param("p", payment).query(String.class).list()).containsExactly("ACCEPTED", "DUPLICATE");
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.payment.confirmed' "
                + "and subject = :p").param("p", payment).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :p and action = "
                + "'PAYMENT_CALLBACK_DUPLICATE'").param("p", payment).query(Long.class).single()).isEqualTo(1);
        JsonNode receipt = body(getAs("/v1/receipts/" + first.get("receiptNumber").asString(), customer("c-uat4")));
        assertThat(receipt.get("type").asString()).isEqualTo("VIOLATION");
        assertThat(receipt.get("totalMinor").asLong()).isEqualTo(50000);
        assertThat(body(getAs("/v1/payables/" + fine, registrar())).get("status").asString()).isEqualTo("PAID");
    }

    @Test
    @Tag("FN-004")
    @DisplayName("a forged signature is refused and the payment stays pending; a FAILED callback fails it")
    void forgedAndFailed() throws Exception {
        String region = newRegion();
        String basket = basket(List.of(payable(region, "VF-F-" + REGIONS.get(), 100)), customer("c-f"));
        String payment = body(postAs("/v1/baskets/" + basket + "/payments", Map.of("method", "GATEWAY_CARD"),
                customer("c-f"), "PORTAL")).get("payment").get("id").asString();
        MvcResult forged = callback(payment, "gw-x", "SUCCESS", 100, "not-the-key");
        assertThat(forged.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(forged)).isEqualTo("SIGNATURE_INVALID");
        assertThat(body(getAs("/v1/payments/" + payment, customer("c-f"))).get("status").asString())
                .isEqualTo("PENDING");
        assertThat(code(postAs("/v1/baskets/" + basket + "/payments", Map.of("method", "GATEWAY_CARD"),
                customer("c-f"), "PORTAL"))).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(body(callback(payment, "gw-x", "FAILED", 100, CALLBACK_KEY)).get("status").asString())
                .isEqualTo("FAILED");
        assertThat(getAs("/v1/payments/" + payment, customer("c-other")).getResponse().getStatus()).isEqualTo(403);
        assertThatThrownBy(() -> db.sql("update payment set amount_minor = 1 where id = cast(:p as uuid)")
                .param("p", payment).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("never changes");
    }

    @Test
    @Tag("FN-022")
    @Tag("FN-001")
    @DisplayName("FN-022: concurrent counter payments get gapless receipt numbers of their region and channel")
    void gaplessUnderConcurrency() throws Exception {
        String region = newRegion();
        int n = 8;
        List<String> baskets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            baskets.add(basket(List.of(payable(region, "VF-G" + i + "-" + REGIONS.get(), 100 + i)), cashier()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<String> numbers = new ArrayList<>();
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (String b : baskets) {
                tasks.add(() -> {
                    MvcResult r = postAs("/v1/baskets/" + b + "/payments", Map.of("method", "CASH",
                            "counterReference", "TILL-" + b.substring(0, 8)), cashier());
                    assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
                    return body(r).get("receipt").get("number").asString();
                });
            }
            for (Future<String> f : pool.invokeAll(tasks)) {
                numbers.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            expected.add("R-" + region + "-BACKOFFICE-" + YEAR + "-" + String.format("%08d", i));
        }
        assertThat(numbers).containsExactlyInAnyOrderElementsOf(expected);
        assertThatThrownBy(() -> db.sql("delete from receipt where region = :r").param("r", region).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never deleted");
    }

    @Test
    @Tag("FN-011")
    @Tag("FN-014")
    @DisplayName("FN-011, FN-014: receipts are searchable; a supervisor cancels on the same day and the item reopens")
    void searchAndCancel() throws Exception {
        String region = newRegion();
        String fine = payable(region, "VF-S-" + REGIONS.get(), 900);
        String basket = basket(List.of(fine), cashier());
        assertThat(code(postAs("/v1/baskets/" + basket + "/payments", Map.of("method", "CASH",
                "counterReference", "TILL-1"), customer("c-s"), "PORTAL"))).isEqualTo("SCOPE_EXCEEDED");
        String number = body(postAs("/v1/baskets/" + basket + "/payments", Map.of("method", "CASH",
                "counterReference", "TILL-1"), cashier())).get("receipt").get("number").asString();
        JsonNode found = body(getAs("/v1/receipts?region=" + region + "&financialItem=FINE_REVENUE", cashier()));
        assertThat(found.get("items").findValuesAsString("number")).containsExactly(number);
        assertThat(getAs("/v1/receipts?region=" + region, customer("c-s")).getResponse().getStatus()).isEqualTo(403);

        assertThat(postAs("/v1/receipts/" + number + "/cancellations", Map.of("reason", "wrong item"), cashier())
                .getResponse().getStatus()).isEqualTo(403);
        JsonNode cancelled = body(postAs("/v1/receipts/" + number + "/cancellations",
                Map.of("reason", "wrong item"), supervisor()));
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(body(getAs("/v1/payables/" + fine, registrar())).get("status").asString()).isEqualTo("OPEN");
        assertThat(code(postAs("/v1/receipts/" + number + "/cancellations", Map.of("reason", "again please"),
                supervisor()))).isEqualTo("RECEIPT_CANCELLED");
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.payment.receipt-cancelled' "
                + "and subject = :n").param("n", number).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Tag("FN-001")
    @DisplayName("FN-001, Q-073: a counter reference is unique per region and method; a repeat there is refused with "
            + "409 and nothing is paid")
    void counterReferenceReused() throws Exception {
        String region = newRegion();
        String reference = "TILL-R" + REGIONS.get();
        assertThat(counterPayment(region, "VF-R1-", "CASH", reference).getResponse().getStatus()).isEqualTo(201);
        // the same slip number in another region, or for another method in this region, is a different reference
        assertThat(counterPayment(newRegion(), "VF-R2-", "CASH", reference).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(counterPayment(region, "VF-R3-", "CARD_TERMINAL", reference).getResponse().getStatus())
                .isEqualTo(201);
        String fine = payable(region, "VF-R4-" + REGIONS.get(), 500);
        MvcResult reused = postAs("/v1/baskets/" + basket(List.of(fine), cashier()) + "/payments",
                Map.of("method", "CASH", "counterReference", reference), cashier());
        assertThat(reused.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(reused)).isEqualTo("TRANSACTION_REFERENCE_USED");
        assertThat(body(getAs("/v1/payables/" + fine, registrar())).get("status").asString()).isEqualTo("OPEN");
    }

    private MvcResult counterPayment(String region, String refPrefix, String method, String reference)
            throws Exception {
        String basket = basket(List.of(payable(region, refPrefix + REGIONS.get(), 400)), cashier());
        return postAs("/v1/baskets/" + basket + "/payments", Map.of("method", method,
                "counterReference", reference), cashier());
    }

    // ---- Increments 4-5: payable holds, tills, refunds, settlement and bank reconciliation

    private static RequestPostProcessor finance(String sub, long refundLimit, String... permissions) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub).claim("roles", List.of("FINANCE"))
                .claim("refundLimit", refundLimit).claim("permissions", Arrays.asList(permissions)));
    }

    private JsonNode expect(MvcResult r, int status) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(status);
        return text.isEmpty() ? null : json.readTree(text);
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** A cash receipt in the region: {paymentId, receiptNumber}. */
    private String[] cashReceipt(String region, long amount) throws Exception {
        String basket = basket(List.of(payable(region, unique("VF-K-"), amount)), cashier());
        JsonNode receipt = expect(postAs("/v1/baskets/" + basket + "/payments", Map.of("method", "CASH",
                "counterReference", unique("TILL-")), cashier()), 201).get("receipt");
        return new String[] {receipt.get("paymentId").asString(), receipt.get("number").asString()};
    }

    /** An online payment confirmed by the gateway with transaction {@code txn}: its id. */
    private String onlinePayment(String region, long amount, String txn) throws Exception {
        String basket = basket(List.of(payable(region, unique("VF-O-"), amount)), customer("c-fin"));
        String payment = expect(postAs("/v1/baskets/" + basket + "/payments", Map.of("method", "GATEWAY_CARD"),
                customer("c-fin"), "PORTAL"), 201).get("payment").get("id").asString();
        assertThat(expect(callback(payment, txn, "SUCCESS", amount, CALLBACK_KEY), 200).get("status").asString())
                .isEqualTo("CONFIRMED");
        return payment;
    }

    @Test
    @Tag("VF-052")
    @DisplayName("VF-052: a payable under objection is paused for collection and resumed after review")
    void payableHolds() throws Exception {
        String region = newRegion();
        String fine = payable(region, unique("VF-H-"), 300);
        RequestPostProcessor objections = user("svc-objection", "OBJECTION_SERVICE", "payment.payable.hold");
        assertThat(postAs("/v1/payables/" + fine + "/holds", Map.of("sourceRef", "OBJ-1", "reason",
                "under objection"), cashier()).getResponse().getStatus()).isEqualTo(403);
        expect(postAs("/v1/payables/" + fine + "/holds", Map.of("sourceRef", "OBJ-1", "reason", "under objection"),
                objections), 204);
        assertThat(code(postAs("/v1/baskets", Map.of("payableIds", List.of(fine)), cashier(), "PORTAL")))
                .isEqualTo("PAYABLE_PAUSED");
        expect(postAs("/v1/payables/" + fine + "/hold-lifts", Map.of("sourceRef", "OBJ-1", "reason",
                "objection rejected"), objections), 204);
        assertThat(code(postAs("/v1/payables/" + fine + "/hold-lifts", Map.of("sourceRef", "OBJ-1", "reason",
                "again please"), objections))).isEqualTo("PAYABLE_HOLD_NOT_ACTIVE");
        String basket = basket(List.of(fine), cashier());
        expect(postAs("/v1/baskets/" + basket + "/payments", Map.of("method", "CASH", "counterReference",
                unique("TILL-H")), cashier()), 201);
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :p and action in "
                + "('PAYABLE_PAUSED', 'PAYABLE_RESUMED')").param("p", fine).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @Tag("FN-020")
    @Tag("FN-013")
    @DisplayName("FN-020: a cashier opens one till per region, takes counter payments and closes it with differences")
    void tillShift() throws Exception {
        String region = newRegion();
        RequestPostProcessor cashierTwo = user("u-cashier-2", "CASHIER", "payment.collect", "payment.till.manage");
        String before = basket(List.of(payable(region, unique("VF-T0-"), 100)), cashierTwo);
        assertThat(code(postAs("/v1/baskets/" + before + "/payments", Map.of("method", "CASH", "counterReference",
                unique("TILL-T0")), cashierTwo))).isEqualTo("TILL_NOT_OPEN");
        JsonNode till = expect(postAs("/v1/tills", Map.of("region", region, "openingAmountMinor", 1000),
                cashierTwo), 201);
        assertThat(code(postAs("/v1/tills", Map.of("region", region, "openingAmountMinor", 0), cashierTwo)))
                .isEqualTo("TILL_ALREADY_OPEN");
        String receipt = expect(postAs("/v1/baskets/" + before + "/payments", Map.of("method", "CASH",
                "counterReference", unique("TILL-T1")), cashierTwo), 201).get("receipt").get("number").asString();
        String basket = basket(List.of(payable(region, unique("VF-T1-"), 400)), cashierTwo);
        expect(postAs("/v1/baskets/" + basket + "/payments", Map.of("method", "CASH", "counterReference",
                unique("TILL-T2")), cashierTwo), 201);
        assertThat(db.sql("select count(*) from ledger_posting l join receipt r on r.id = l.receipt_id where r.number = :n").param("n", receipt)
                .query(Long.class).single()).as("FN-013 ledger posting per receipt line").isPositive();

        String id = till.get("id").asString();
        Map<String, Object> close = Map.of("totals", List.of(Map.of("method", "CASH", "actualMinor", 1400)),
                "reason", "end of shift");
        assertThat(code(postAs("/v1/tills/" + id + "/closures", close, cashier()))).isEqualTo("TILL_OWNER_REQUIRED");
        JsonNode closed = expect(postAs("/v1/tills/" + id + "/closures", close, cashierTwo), 200);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("totals")).hasSize(3);
        for (JsonNode t : closed.get("totals")) {
            if ("CASH".equals(t.get("method").asString())) {
                assertThat(t.get("expectedMinor").asLong()).as("opening 1000 + 100 + 400").isEqualTo(1500);
                assertThat(t.get("differenceMinor").asLong()).isEqualTo(-100);
            }
        }
        assertThat(code(postAs("/v1/tills/" + id + "/closures", close, cashierTwo))).isEqualTo("TILL_ALREADY_CLOSED");
        assertThat(code(postAs("/v1/tills/" + UUID.randomUUID() + "/closures", close, cashierTwo)))
                .isEqualTo("TILL_NOT_FOUND");
    }

    @Test
    @Tag("FN-012")
    @Tag("CC-002")
    @Tag("CC-003")
    @DisplayName("FN-012: a maker requests a refund, another user decides it within their limit; online refunds go "
            + "back through the gateway")
    void refunds() throws Exception {
        String region = newRegion();
        String[] cash = cashReceipt(region, 1000);
        RequestPostProcessor maker = finance("u-maker", 0, "payment.refund.request");
        RequestPostProcessor small = finance("u-small", 100, "payment.refund.approve");
        RequestPostProcessor checker = finance("u-checker", 100_000, "payment.refund.approve");
        assertThat(code(postAs("/v1/payments/" + cash[0] + "/refund-requests", Map.of("amountMinor", 1001,
                "reason", "overpaid"), maker))).isEqualTo("REFUND_EXCEEDS_PAID");
        assertThat(code(postAs("/v1/payments/" + UUID.randomUUID() + "/refund-requests", Map.of("amountMinor", 1,
                "reason", "overpaid"), maker))).isEqualTo("PAYMENT_NOT_FOUND");
        JsonNode refund = expect(postAs("/v1/payments/" + cash[0] + "/refund-requests", Map.of("amountMinor", 600,
                "reason", "overpaid"), maker), 201);
        assertThat(refund.get("status").asString()).isEqualTo("PENDING");
        assertThat(refund.get("receiptNumber").asString()).isEqualTo(cash[1]);
        assertThat(code(postAs("/v1/payments/" + cash[0] + "/refund-requests", Map.of("amountMinor", 500,
                "reason", "again"), maker))).as("600 of 1000 already requested").isEqualTo("REFUND_EXCEEDS_PAID");
        String id = refund.get("id").asString();
        RequestPostProcessor makerChecking = finance("u-maker", 100_000, "payment.refund.approve");
        assertThat(code(postAs("/v1/refund-requests/" + id + "/approvals", Map.of("reason", "ok ok"),
                makerChecking))).isEqualTo("SAME_MAKER_CHECKER");
        assertThat(code(postAs("/v1/refund-requests/" + id + "/approvals", Map.of("reason", "ok ok"), small)))
                .isEqualTo("APPROVAL_LIMIT_EXCEEDED");
        JsonNode approved = expect(postAs("/v1/refund-requests/" + id + "/approvals", Map.of("reason", "checked"),
                checker), 200);
        assertThat(approved.get("status").asString()).as("cash: paid out at the counter").isEqualTo("APPROVED");
        assertThat(code(postAs("/v1/refund-requests/" + id + "/rejections", Map.of("reason", "too late"), checker)))
                .isEqualTo("REFUND_DECIDED");
        assertThat(code(postAs("/v1/refund-requests/" + UUID.randomUUID() + "/approvals", Map.of("reason", "ok ok"),
                checker))).isEqualTo("REFUND_NOT_FOUND");
        String second = expect(postAs("/v1/payments/" + cash[0] + "/refund-requests", Map.of("amountMinor", 400,
                "reason", "the rest"), maker), 201).get("id").asString();
        assertThat(expect(postAs("/v1/refund-requests/" + second + "/rejections", Map.of("reason", "not owed"),
                checker), 200).get("status").asString()).isEqualTo("REJECTED");

        String online = onlinePayment(region, 800, unique("gw-rf-"));
        String onlineRefund = expect(postAs("/v1/payments/" + online + "/refund-requests", Map.of("amountMinor", 800,
                "reason", "service not delivered"), maker), 201).get("id").asString();
        JsonNode submitted = expect(postAs("/v1/refund-requests/" + onlineRefund + "/approvals",
                Map.of("reason", "checked"), checker), 200);
        assertThat(submitted.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(submitted.get("remoteRef").asString()).startsWith("rf-");
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.payment.refund-approved' "
                + "and subject in (:a, :b)").param("a", id).param("b", onlineRefund).query(Long.class).single())
                .isEqualTo(2);
        assertThat(postAs("/v1/payments/" + cash[0] + "/refund-requests", Map.of("amountMinor", 1, "reason", "x y z"),
                cashier()).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @Tag("FN-040")
    @Tag("FN-041")
    @Tag("FN-006")
    @DisplayName("FN-040: settlement lines match confirmed payments; the rest are exceptions resolved with a reason")
    void settlementReconciliation() throws Exception {
        String region = newRegion();
        String n = unique("");
        onlinePayment(region, 700, "gw-s1-" + n);
        RequestPostProcessor importer = user("svc-settlement", "SETTLEMENT_IMPORTER", "payment.settlement.import");
        RequestPostProcessor officer = user("u-fin", "FINANCE_OFFICER", "payment.finance.read",
                "payment.finance.resolve");
        Map<String, Object> matched = Map.of("source", "GATEWAY", "externalRef", "gw-s1-" + n, "region", region,
                "currency", "XTS", "amountMinor", 700);
        Map<String, Object> early = Map.of("source", "GATEWAY", "externalRef", "gw-s2-" + n, "region", region,
                "currency", "XTS", "amountMinor", 900);
        JsonNode imported = expect(postAs("/v1/settlement-imports", Map.of("lines", List.of(matched, early)),
                importer), 200);
        assertThat(imported.get("items").findValuesAsString("status")).containsExactlyInAnyOrder("MATCHED",
                "EXCEPTION");
        String exception = null;
        for (JsonNode item : expect(getAs("/v1/reconciliation-exceptions?limit=1000", officer), 200).get("items")) {
            if (item.get("externalRef").asString().equals("gw-s2-" + n)) {
                exception = item.get("id").asString();
            }
        }
        assertThat(exception).isNotNull();
        String wrong = onlinePayment(region, 901, "gw-s3-" + n);
        assertThat(code(postAs("/v1/reconciliation-exceptions/" + exception + "/resolutions",
                Map.of("paymentId", wrong, "reason", "matched by hand"), officer))).isEqualTo("RECONCILIATION_MISMATCH");
        String late = onlinePayment(region, 900, "gw-s2-" + n);
        JsonNode resolved = expect(postAs("/v1/reconciliation-exceptions/" + exception + "/resolutions",
                Map.of("paymentId", late, "reason", "callback arrived after the file"), officer), 200);
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(code(postAs("/v1/reconciliation-exceptions/" + exception + "/resolutions",
                Map.of("paymentId", late, "reason", "again please"), officer))).isEqualTo("RECONCILIATION_RESOLVED");
        assertThat(getAs("/v1/reconciliation-exceptions", importer).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @Tag("FN-021")
    @Tag("FN-041")
    @DisplayName("FN-021: receipts are grouped into bank deposits and matched to the bank statement")
    void bankDeposits() throws Exception {
        String region = newRegion();
        String n = unique("");
        String[] a = cashReceipt(region, 300);
        String[] b = cashReceipt(region, 200);
        String[] c = cashReceipt(region, 150);
        RequestPostProcessor importer = user("u-dep", "FINANCE_OFFICER", "payment.settlement.import");
        RequestPostProcessor officer = user("u-fin", "FINANCE_OFFICER", "payment.finance.read",
                "payment.finance.resolve");
        JsonNode deposit = expect(postAs("/v1/bank-deposits", Map.of("depositRef", "DEP1-" + n, "bank", "BANK A",
                "receiptNumbers", List.of(a[1], b[1])), importer), 201);
        assertThat(deposit.get("totalMinor").asLong()).isEqualTo(500);
        assertThat(code(postAs("/v1/bank-deposits", Map.of("depositRef", "DEP1-" + n, "bank", "BANK A",
                "receiptNumbers", List.of(c[1])), importer))).isEqualTo("BANK_DEPOSIT_REFERENCE_EXISTS");
        assertThat(code(postAs("/v1/bank-deposits", Map.of("depositRef", "DEPX-" + n, "bank", "BANK A",
                "receiptNumbers", List.of("R-NOPE")), importer))).isEqualTo("BANK_DEPOSIT_RECEIPTS_INVALID");

        Map<String, Object> exact = Map.of("source", "BANK_A", "externalRef", "DEP1-" + n, "bank", "BANK A",
                "region", region, "currency", "XTS", "amountMinor", 500);
        Map<String, Object> early = Map.of("source", "BANK_A", "externalRef", "DEP2-" + n, "bank", "BANK A",
                "region", region, "currency", "XTS", "amountMinor", 150);
        JsonNode lines = expect(postAs("/v1/bank-statement-imports", Map.of("lines", List.of(exact, early)),
                importer), 200);
        assertThat(lines.get("items").findValuesAsString("status")).containsExactlyInAnyOrder("MATCHED", "EXCEPTION");

        String line = null;
        for (JsonNode item : expect(getAs("/v1/bank-deposit-exceptions?limit=1000", officer), 200).get("items")) {
            if (item.get("externalRef").asString().equals("DEP2-" + n)) {
                line = item.get("id").asString();
            }
        }
        assertThat(line).isNotNull();
        assertThat(code(postAs("/v1/bank-deposit-exceptions/" + line + "/resolutions", Map.of("depositId",
                deposit.get("id").asString(), "reason", "by hand"), officer))).isEqualTo("BANK_DEPOSIT_MISMATCH");
        String second = expect(postAs("/v1/bank-deposits", Map.of("depositRef", "DEP2-" + n, "bank", "BANK A",
                "receiptNumbers", List.of(c[1])), importer), 201).get("id").asString();
        JsonNode resolved = expect(postAs("/v1/bank-deposit-exceptions/" + line + "/resolutions", Map.of("depositId",
                second, "reason", "deposit slip recorded late"), officer), 200);
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(code(postAs("/v1/bank-deposit-exceptions/" + line + "/resolutions", Map.of("depositId", second,
                "reason", "again please"), officer))).isEqualTo("BANK_STATEMENT_RESOLVED");
        assertThat(db.sql("select status from bank_deposit where id = cast(:d as uuid)").param("d", second)
                .query(String.class).single()).isEqualTo("MATCHED");
    }
}

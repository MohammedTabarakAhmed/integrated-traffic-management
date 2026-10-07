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
import java.util.List;
import java.util.Map;
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
        return user("u-cashier", "CASHIER", "payment.collect", "payment.receipt.read");
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
        boolean idempotent = path.equals("/v1/payables") || path.equals("/v1/baskets")
                || path.endsWith("/payments") || path.startsWith("/v1/receipts/");
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

    private String payable(String region, String ref, long amount) throws Exception {
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
        assertThat(forged.getResponse().getStatus()).isEqualTo(403);
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
}

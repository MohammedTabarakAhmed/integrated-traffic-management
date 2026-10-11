package gov.itms.notification.api;

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
import gov.itms.notification.application.NotificationService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * End-to-end slice test of notifications: HTTP → security → service → PostgreSQL (V1 guards), and the dispatcher →
 * a real HTTP stand-in for the messaging gateway (ICD-05). Every request and response is validated against
 * notification-service.v1.yaml. Jobs are off; the test calls the dispatcher itself. Tests share one database, so each
 * uses its own template codes and recipients.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.notifications.jobs.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("CC-070")
class NotificationApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final GatewayStub GATEWAY = new GatewayStub();
    private static final AtomicInteger CODES = new AtomicInteger();
    private static OpenApiInteractionValidator contract;

    @DynamicPropertySource
    static void gateways(DynamicPropertyRegistry registry) {
        registry.add("itms.notifications.gateways.SMS", () -> GATEWAY.baseUrl() + "/sms");
        registry.add("itms.notifications.gateways.EMAIL", () -> GATEWAY.baseUrl() + "/email");
        registry.add("itms.notifications.gateways.PUSH", () -> GATEWAY.baseUrl() + "/push");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    @Autowired
    NotificationService notifications;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiInteractionValidator.createFor("/openapi/notification-service.v1.yaml").build();
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.stop();
    }

    private static RequestPostProcessor admin() {
        return user("u-admin", "NOTIFICATION_ADMIN", "notification.template.manage", "notification.template.read",
                "notification.subscription.manage");
    }

    private static RequestPostProcessor sender() {
        return user("svc-vehicle", "NOTIFICATION_SENDER", "notification.send", "notification.expiry.register");
    }

    private static RequestPostProcessor support() {
        return user("u-support", "NOTIFICATION_SUPPORT", "notification.read", "notification.recipient.manage");
    }

    private static RequestPostProcessor gatewayAdapter() {
        return user("svc-gateway", "NOTIFICATION_GATEWAY", "notification.receipt");
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
        boolean idempotent = path.equals("/v1/notification-templates") || path.equals("/v1/notifications");
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
        return "TPL" + CODES.incrementAndGet() + "_" + Long.toString(System.nanoTime() % 1_000_000, 36).toUpperCase();
    }

    private String published(boolean mandatory) throws Exception {
        String code = newCode();
        Map<String, Object> request = Map.of("code", code, "category", "VEHICLE", "mandatory", mandatory,
                "contents", Map.of(
                        "SMS", Map.of("bodyNl", "رقم {{plate}}", "bodyEn", "Plate {{plate}} renewed"),
                        "INBOX", Map.of("subjectNl", "تجديد", "subjectEn", "Renewal", "bodyNl", "رقم {{plate}}",
                                "bodyEn", "Plate {{plate}} renewed")),
                "reason", "first version");
        MvcResult r = postAs("/v1/notification-templates", request, admin());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return code;
    }

    private JsonNode send(String template, String recipient) throws Exception {
        MvcResult r = postAs("/v1/notifications", Map.of("templateCode", template, "recipientId", recipient,
                "data", Map.of("plate", "A 123"), "reference", "REF-" + recipient), sender());
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("items");
    }

    @Test
    @Tag("CC-074")
    @DisplayName("send → SMS dispatched to the gateway → delivery receipt; inbox delivered at once; destination masked")
    void sendDispatchReceipt() throws Exception {
        String template = published(false);
        String recipient = "c-" + template.toLowerCase();
        postAs("/v1/recipients/" + recipient, Map.of("language", "EN", "phone", "+97150123456"), customer(recipient));

        JsonNode items = send(template, recipient);
        assertThat(items.findValuesAsString("channel")).containsExactlyInAnyOrder("SMS", "INBOX");
        JsonNode sms = items.get(0).get("channel").asString().equals("SMS") ? items.get(0) : items.get(1);
        assertThat(sms.get("status").asString()).isEqualTo("QUEUED");
        assertThat(sms.get("destination").asString()).isEqualTo("*********456");
        assertThat(sms.get("body").asString()).isEqualTo("Plate A 123 renewed");

        notifications.dispatch(50);
        String smsId = sms.get("id").asString();
        assertThat(GATEWAY.received).anyMatch(m -> m.contains("\"clientReference\":\"" + smsId + "\"")
                && m.contains("+97150123456"));
        JsonNode sent = body(getAs("/v1/notifications/" + smsId, support()));
        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        assertThat(sent.get("attempts").asInt()).isEqualTo(1);

        MvcResult receipt = postAs("/v1/delivery-receipts", Map.of("messageId", "gw-" + smsId, "delivered", true),
                gatewayAdapter());
        assertThat(body(receipt).get("status").asString()).isEqualTo("DELIVERED");
        assertThat(postAs("/v1/delivery-receipts", Map.of("messageId", "gw-" + smsId, "delivered", true),
                gatewayAdapter()).getResponse().getStatus()).isEqualTo(409);
        assertThat(postAs("/v1/delivery-receipts", Map.of("messageId", "gw-none", "delivered", true),
                gatewayAdapter()).getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs("/v1/delivery-receipts", Map.of("messageId", "gw-" + smsId, "delivered", true),
                support()).getResponse().getStatus()).isEqualTo(403);
        assertThat(db.sql("select count(*) from audit_outbox where entity_id = :id and action = "
                + "'NOTIFICATION_DELIVERED'").param("id", smsId).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> db.sql("delete from notification where id = cast(:id as uuid)").param("id", smsId)
                .update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    @Tag("CC-073")
    @Tag("CC-001")
    @DisplayName("own scope: customers see their inbox and recipient; other customers and plain staff do not")
    void ownScope() throws Exception {
        String template = published(false);
        String recipient = "c-" + template.toLowerCase();
        postAs("/v1/recipients/" + recipient, Map.of("language", "EN", "email", "a.person@example.org",
                "phone", "+97150123456", "preferences", Map.of("VEHICLE", List.of("INBOX"))), customer(recipient));
        assertThat(postAs("/v1/recipients/" + recipient, Map.of("language", "NL"), customer("c-other"))
                .getResponse().getStatus()).isEqualTo(403);
        JsonNode me = body(getAs("/v1/recipients/" + recipient, customer(recipient)));
        assertThat(me.get("email").asString()).isEqualTo("a***@example.org");
        assertThat(getAs("/v1/recipients/" + recipient, customer("c-other")).getResponse().getStatus())
                .isEqualTo(403);

        JsonNode items = send(template, recipient);
        assertThat(items.findValuesAsString("channel")).as("preferences narrow optional notices")
                .containsExactly("INBOX");
        String inboxId = items.get(0).get("id").asString();
        JsonNode inbox = body(getAs("/v1/inbox?unreadOnly=true", customer(recipient))).get("items");
        assertThat(inbox.findValuesAsString("id")).containsExactly(inboxId);
        assertThat(body(getAs("/v1/inbox", customer("c-other"))).get("items")).isEmpty();
        assertThat(postAs("/v1/inbox/" + inboxId + "/reads", null, customer("c-other")).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(body(postAs("/v1/inbox/" + inboxId + "/reads", null, customer(recipient))).get("readAt").isNull())
                .isFalse();
        assertThat(body(getAs("/v1/inbox?unreadOnly=true", customer(recipient))).get("items")).isEmpty();
        assertThat(getAs("/v1/notifications/" + inboxId, customer("c-other")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(body(getAs("/v1/notifications?recipientId=" + recipient, support())).get("items")).hasSize(1);

        String legal = published(true);
        assertThat(send(legal, recipient).findValuesAsString("channel")).as("mandatory ignores preferences")
                .containsExactlyInAnyOrder("SMS", "INBOX");
    }

    @Test
    @Tag("CC-071")
    @Tag("CC-072")
    @DisplayName("subscriptions and expiry watches are saved; events trigger templates once")
    void subscriptionsAndExpiry() throws Exception {
        String template = published(false);
        String eventType = "itms.vehicle.it-" + template.toLowerCase().replace('_', '-');
        MvcResult saved = postAs("/v1/event-subscriptions", Map.of("eventType", eventType, "templateCode", template,
                "recipientField", "ownerId", "referenceField", "registrationNo", "active", true), admin());
        assertThat(saved.getResponse().getStatus()).as(saved.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(getAs("/v1/event-subscriptions", admin())).get("items").findValuesAsString("eventType"))
                .contains(eventType);
        assertThat(code(postAs("/v1/event-subscriptions", Map.of("eventType", eventType, "templateCode", "NO_SUCH",
                "recipientField", "ownerId", "active", true), admin()))).isEqualTo("TEMPLATE_NOT_FOUND");

        String recipient = "c-" + template.toLowerCase();
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> data = new HashMap<>(Map.of("ownerId", recipient, "registrationNo", "REG-1",
                "plate", "B 7"));
        assertThat(notifications.onEvent(eventId, eventType, data)).isEqualTo(1);
        assertThat(notifications.onEvent(eventId, eventType, data)).isZero();
        assertThat(db.sql("select count(*) from notification where recipient_id = :r and source_event_id = :e")
                .param("r", recipient).param("e", eventId).query(Long.class).single()).isEqualTo(1);

        MvcResult watch = postAs("/v1/expiry-watches", Map.of("subjectType", "LICENCE", "subjectId",
                "DL-" + CODES.get(), "recipientId", recipient, "expiryDate", "2030-01-31"), sender());
        assertThat(watch.getResponse().getStatus()).as(watch.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(postAs("/v1/expiry-watches", Map.of("subjectType", "LICENCE", "subjectId", "DL-1",
                "recipientId", recipient, "expiryDate", "2030-01-31"), admin()).getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    @Tag("VF-015")
    @Tag("VF-031")
    @Tag("UAT-1")
    @DisplayName("VF-015, VF-031, UAT-1: the seeded violation notices are sent to the responsible party's inbox")
    void violationNotices() {
        String file = "TF-VIO-" + CODES.incrementAndGet();
        Map<String, Map<String, Object>> events = Map.of(
                "itms.violation.recorded", Map.of("violationNumber", "V-2026-000000901", "plate", "S 1",
                        "occurredAt", "2026-10-09T08:00:00Z", "points", 6, "fine", 50000, "recipientId", file),
                "itms.violation.points-warning", Map.of("violationNumber", "V-2026-000000901", "points", 6,
                        "activePoints", 18, "warningThreshold", 18, "suspensionThreshold", 24, "recipientId", file),
                "itms.violation.licence-suspended", Map.of("violationNumber", "V-2026-000000901", "months", 3,
                        "from", "2026-10-09", "until", "2027-01-08", "activePoints", 26, "recipientId", file));
        events.forEach((type, data) -> {
            String eventId = UUID.randomUUID().toString();
            assertThat(notifications.onEvent(eventId, type, new HashMap<>(data))).as(type).isEqualTo(1);
            assertThat(db.sql("select count(*) from notification where recipient_id = :r and source_event_id = :e "
                            + "and channel = 'INBOX' and mandatory and reference = 'V-2026-000000901' "
                            + "and body like '%V-2026-000000901%'")
                    .param("r", file).param("e", eventId).query(Long.class).single()).as(type).isEqualTo(1);
        });
    }

    @Test
    @Tag("CC-062")
    @Tag("CC-071")
    @DisplayName("CC-062: an overdue case notifies its customer of the delay; a case without a customer is skipped")
    void caseDelayNotice() {
        String customer = "TF-DELAY-" + CODES.incrementAndGet();
        String reference = "VEH_RENEWAL-NORTH-2026-" + CODES.incrementAndGet();
        Map<String, Object> overdue = new HashMap<>(Map.of("itemId", UUID.randomUUID().toString(), "reference",
                reference, "serviceCode", "VEH_RENEWAL", "status", "ESCALATED", "customerId", customer,
                "overdueSince", "2026-10-09T08:00:00Z"));
        String eventId = UUID.randomUUID().toString();
        assertThat(notifications.onEvent(eventId, "itms.workflow.item-overdue", overdue)).isEqualTo(1);
        assertThat(db.sql("select count(*) from notification where recipient_id = :r and source_event_id = :e "
                        + "and channel = 'INBOX' and not mandatory and reference = :ref and body like :body")
                .param("r", customer).param("e", eventId).param("ref", reference).param("body", "%" + reference + "%")
                .query(Long.class).single()).isEqualTo(1);

        overdue.remove("customerId");
        assertThat(notifications.onEvent(UUID.randomUUID().toString(), "itms.workflow.item-overdue", overdue))
                .as("no customer on the case").isZero();
    }

    @Test
    @DisplayName("ADR-032: a repeated send with the same Idempotency-Key replays and queues nothing new")
    void idempotentSend() throws Exception {
        String template = published(false);
        String recipient = "c-" + template.toLowerCase();
        Map<String, Object> request = Map.of("templateCode", template, "recipientId", recipient, "data",
                Map.of("plate", "A 1"));
        String key = "it-" + UUID.randomUUID();
        call(post("/v1/notifications"), Request.Method.POST, "/v1/notifications", request, sender(), key);
        MvcResult retry = call(post("/v1/notifications"), Request.Method.POST, "/v1/notifications", request,
                sender(), key);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(db.sql("select count(*) from notification where recipient_id = :r").param("r", recipient)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(code(postAs("/v1/notifications", Map.of("templateCode", template, "recipientId", recipient),
                sender()))).isEqualTo("TEMPLATE_DATA_MISSING");
    }

    /** A real HTTP stand-in for the messaging gateway: accepts every message with id {@code gw-<clientReference>}. */
    static final class GatewayStub {
        final List<String> received = new CopyOnWriteArrayList<>();
        private final HttpServer server;
        private final ObjectMapper mapper = new ObjectMapper();

        GatewayStub() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.add(body);
                String reference = mapper.readTree(body).get("clientReference").asString();
                byte[] answer = ("{\"messageId\":\"gw-" + reference + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, answer.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(answer);
                }
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }
}

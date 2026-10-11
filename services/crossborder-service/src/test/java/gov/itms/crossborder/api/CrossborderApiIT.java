package gov.itms.crossborder.api;

import static gov.itms.crossborder.api.ContractClient.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gov.itms.crossborder.application.CrossborderService;
import gov.itms.crossborder.application.ExchangeDelivery;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cross-border exchange (XB-001..XB-008, DL-064) against an HTTP stand-in for the partner state's endpoint; every
 * request and response is checked against crossborder-service.v1.yaml. Scheduled jobs are off; the tests call the
 * delivery endpoint, and the event consumers' entry points on the service directly.
 */
@SpringBootTest(properties = {
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims",
        "itms.crossborder.jobs-enabled=false",
        "itms.crossborder.local-state=HOME",
        "itms.crossborder.retry-base=PT1S",
        "itms.crossborder.retry-max=PT2S"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("XB-001")
class CrossborderApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    /** Received envelopes; {@link #answer} is the HTTP status the partner returns next. */
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicInteger ANSWER = new AtomicInteger(202);
    private static final HttpServer PARTNER = partner();
    private static final AtomicInteger CODES = new AtomicInteger();

    private static HttpServer partner() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            s.createContext("/", CrossborderApiIT::answer);
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void answer(HttpExchange ex) throws IOException {
        RECEIVED.add(ex.getRequestURI().getPath() + " " + new String(ex.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        int status = ANSWER.get();
        byte[] body = ("{\"reference\":\"PR-" + RECEIVED.size() + "\"}").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    @AfterAll
    static void stop() {
        PARTNER.stop(0);
    }

    private static final RequestPostProcessor MAKER = user("u-maker", "CROSSBORDER_ADMIN",
            "crossborder.partner.manage", "crossborder.mapping.manage", "crossborder.message.read");
    private static final RequestPostProcessor CHECKER = user("u-checker", "CROSSBORDER_APPROVER",
            "crossborder.proposal.approve");
    private static final RequestPostProcessor OFFICER = user("u-xb", "CROSSBORDER_OFFICER", "crossborder.exchange",
            "crossborder.message.read", "crossborder.objection.submit", "crossborder.data.exchange",
            "crossborder.settlement.read", "crossborder.settlement.issue");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient db;
    @Autowired
    CrossborderService exchange;
    @Autowired
    ExchangeDelivery delivery;
    private ContractClient api;

    @BeforeEach
    void client() {
        api = new ContractClient(mvc, json, "/openapi/crossborder-service.v1.yaml");
        ANSWER.set(202);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + PARTNER.getAddress().getPort() + "/xb";
    }

    private String approve(MvcResult proposed) throws Exception {
        JsonNode p = api.expect(proposed, 202);
        api.expect(api.post("/v1/config-proposals/" + p.get("id").asString() + "/decisions", Map.of("approve", true,
                "reason", "agreement signed"), CHECKER), 200);
        return p.get("id").asString();
    }

    /** A partner state configured and approved, with SPEED_20 mapped to P_SPD: its code. */
    private String partner(String mode, boolean allowsData) throws Exception {
        String code = "P" + CODES.incrementAndGet() + (char) ('A' + CODES.get() % 26);
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Partner " + code, "mode", mode,
                "commissionBasisPoints", 1000, "allowsAccident", allowsData, "allowsPermit", allowsData,
                "allowsLicence", allowsData, "reason", "bilateral agreement"));
        body.put("endpointUrl", endpoint());
        approve(api.post("/v1/partner-states", body, MAKER));
        approve(api.post("/v1/partner-states/" + code + "/mappings", Map.of("domain", "VIOLATION", "localCode",
                "SPEED_20", "externalCode", "P_SPD", "reason", "code list v1"), MAKER));
        return code;
    }

    private Map<String, Object> outbound(String reference) {
        return new HashMap<>(Map.of("reference", reference, "code", "SPEED_20", "vehiclePlate", "PS 123", "currency",
                "XTS", "amountMinor", 50_000, "occurredAt", Instant.now().minusSeconds(3600).toString(), "location",
                "E11 km 12"));
    }

    private Map<String, Object> inbound(String externalId, long amount) {
        return new HashMap<>(Map.of("externalId", externalId, "code", "P_SPD", "vehiclePlate", "A 1234", "currency",
                "XTS", "amountMinor", amount, "occurredAt", Instant.now().minusSeconds(7200).toString()));
    }

    private String status(String messageId) throws Exception {
        return api.expect(api.get("/v1/exchange-messages/" + messageId, OFFICER), 200).get("status").asString();
    }

    @Test
    @Tag("XB-007")
    @Tag("CC-002")
    @DisplayName("XB-007: partner settings and code mappings change only by maker-checker")
    void makerChecker() throws Exception {
        String code = "M" + CODES.incrementAndGet() + "X";
        Map<String, Object> body = Map.of("code", code, "name", "Partner", "mode", "REALTIME", "commissionBasisPoints",
                500, "allowsAccident", false, "allowsPermit", false, "allowsLicence", false, "reason", "new partner");
        JsonNode p = api.expect(api.post("/v1/partner-states", body, MAKER), 202);
        assertThat(api.post("/v1/partner-states", body, MAKER).getResponse().getStatus()).as("one open proposal")
                .isEqualTo(409);
        RequestPostProcessor makerApproving = user("u-maker", "CROSSBORDER_APPROVER", "crossborder.proposal.approve");
        assertThat(api.post("/v1/config-proposals/" + p.get("id").asString() + "/decisions", Map.of("approve", true,
                "reason", "self"), makerApproving).getResponse().getStatus()).isEqualTo(409);
        assertThat(api.expect(api.get("/v1/config-proposals?limit=500", CHECKER), 200).get("items").size())
                .isPositive();
        assertThat(api.get("/v1/partner-states/" + code, MAKER).getResponse().getStatus()).isEqualTo(404);
        api.expect(api.post("/v1/config-proposals/" + p.get("id").asString() + "/decisions", Map.of("approve", false,
                "reason", "wrong commission"), CHECKER), 200);
        assertThat(api.post("/v1/config-proposals/" + p.get("id").asString() + "/decisions", Map.of("approve", true,
                "reason", "again"), CHECKER).getResponse().getStatus()).isEqualTo(409);
        approve(api.post("/v1/partner-states", body, MAKER));
        assertThat(api.expect(api.get("/v1/partner-states/" + code, MAKER), 200).get("mode").asString())
                .isEqualTo("REALTIME");
        assertThat(api.post("/v1/partner-states", Map.of("code", "bad", "name", "x", "mode", "WEEKLY",
                "commissionBasisPoints", 1, "allowsAccident", false, "allowsPermit", false, "allowsLicence", false,
                "reason", "nope"), MAKER).getResponse().getStatus()).isEqualTo(400);
        assertThat(api.post("/v1/partner-states/NOPE/mappings", Map.of("domain", "VIOLATION", "localCode", "A",
                "externalCode", "B", "reason", "x y z"), MAKER).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @Tag("XB-008")
    @DisplayName("XB-001/008: an outbound violation is delivered in real time with the partner's reference; retries "
            + "on a server error; refused for good on a client error; every status change kept")
    void outboundDelivery() throws Exception {
        String partner = partner("REALTIME", false);
        String ref = "V-OUT-" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode m = api.expect(api.post("/v1/partner-states/" + partner + "/violations", outbound(ref), OFFICER), 201);
        assertThat(m.get("status").asString()).isEqualTo("PENDING");
        assertThat(m.get("mappedCode").asString()).isEqualTo("P_SPD");
        assertThat(api.expect(api.post("/v1/partner-states/" + partner + "/violations", outbound(ref), OFFICER), 201)
                .get("id")).as("a repeat returns the first").isEqualTo(m.get("id"));
        Map<String, Object> unmapped = outbound("V-X-" + UUID.randomUUID().toString().substring(0, 6));
        unmapped.put("code", "PARKING_9");
        assertThat(api.post("/v1/partner-states/" + partner + "/violations", unmapped, OFFICER).getResponse()
                .getStatus()).isEqualTo(400);

        assertThat(api.expect(api.post("/v1/partner-states/" + partner + "/batches", Map.of(), OFFICER), 200)
                .get("accepted").asInt()).isEqualTo(1);
        assertThat(RECEIVED.getLast()).startsWith("/xb/messages").contains("\"sender\":\"HOME\"", "P_SPD");
        String id = m.get("id").asString();
        assertThat(status(id)).isEqualTo("SENT");
        assertThat(api.expect(api.get("/v1/exchange-messages/" + id + "/history", OFFICER), 200).get("items").size())
                .isGreaterThanOrEqualTo(2);

        String retried = api.expect(api.post("/v1/partner-states/" + partner + "/violations",
                outbound("V-R-" + UUID.randomUUID().toString().substring(0, 6)), OFFICER), 201).get("id").asString();
        ANSWER.set(503);
        assertThat(api.expect(api.post("/v1/partner-states/" + partner + "/batches", Map.of(), OFFICER), 200)
                .get("accepted").asInt()).isZero();
        assertThat(status(retried)).as("retried later").isEqualTo("PENDING");
        ANSWER.set(202);
        Thread.sleep(2_200);
        assertThat(delivery.deliver(partner, null, null)).isEqualTo(1);
        assertThat(status(retried)).isEqualTo("SENT");

        String refused = api.expect(api.post("/v1/partner-states/" + partner + "/violations",
                outbound("V-F-" + UUID.randomUUID().toString().substring(0, 6)), OFFICER), 201).get("id").asString();
        ANSWER.set(400);
        delivery.deliver(partner, null, null);
        assertThat(status(refused)).isEqualTo("REJECTED");
        assertThatHistoryIsImmutable(id);
    }

    private void assertThatHistoryIsImmutable(String id) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql(
                "delete from exchange_message_history where message_id = cast(:id as uuid)").param("id", id).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Tag("XB-008")
    @DisplayName("XB-008: a daily-batch partner receives one batch; a later acknowledgement is applied")
    void dailyBatchAndAcknowledgement() throws Exception {
        String partner = partner("DAILY_BATCH", false);
        String a = api.expect(api.post("/v1/partner-states/" + partner + "/violations",
                outbound("V-B1-" + UUID.randomUUID().toString().substring(0, 6)), OFFICER), 201).get("id").asString();
        api.expect(api.post("/v1/partner-states/" + partner + "/violations",
                outbound("V-B2-" + UUID.randomUUID().toString().substring(0, 6)), OFFICER), 201);
        assertThat(delivery.deliverAll("DAILY_BATCH")).isGreaterThanOrEqualTo(2);
        assertThat(RECEIVED.getLast()).startsWith("/xb/batches");
        assertThat(status(a)).isEqualTo("SENT");
        String inboundId = api.expect(api.post("/v1/partner-states/" + partner + "/inbound-violations",
                inbound("E-ACK-" + UUID.randomUUID(), 100), OFFICER), 201).get("id").asString();
        assertThat(api.post("/v1/exchange-messages/" + inboundId + "/acknowledgements", Map.of("accepted", true),
                OFFICER).getResponse().getStatus()).as("only outbound messages").isEqualTo(400);
        assertThat(api.expect(api.post("/v1/exchange-messages/" + a + "/acknowledgements", Map.of("accepted", true,
                "partnerReference", "PB-1"), OFFICER), 200).get("id").asString()).isEqualTo(a);
    }

    @Test
    @Tag("XB-002")
    @Tag("XB-003")
    @Tag("XB-004")
    @Tag("UAT-5")
    @DisplayName("XB-002/003/004: an inbound violation is loaded, paid in the partner state; a violation paid here "
            + "sends a notice; the settlement nets both after commission")
    void inboundPaymentAndSettlement() throws Exception {
        String partner = partner("REALTIME", false);
        Instant from = Instant.now().minusSeconds(5);
        String ext = "E-" + UUID.randomUUID();
        JsonNode in = api.expect(api.post("/v1/partner-states/" + partner + "/inbound-violations", inbound(ext, 30_000),
                OFFICER), 201);
        assertThat(in.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(in.get("mappedCode").asString()).isEqualTo("SPEED_20");
        assertThat(api.expect(api.post("/v1/partner-states/" + partner + "/inbound-violations", inbound(ext, 30_000),
                OFFICER), 201).get("id")).isEqualTo(in.get("id"));
        assertThat(exchange.violationLoaded(partner, ext, "V-LOCAL-1")).isTrue();
        assertThat(exchange.violationLoaded(partner, ext, "V-LOCAL-1")).isFalse();
        // the partner's fine is paid here: a payment notice goes out and this state owes the partner
        assertThat(exchange.paidHere("V-LOCAL-1")).isPresent();
        assertThat(exchange.paidHere("V-LOCAL-1")).as("once").isEmpty();
        delivery.deliver(partner, null, null);
        assertThat(RECEIVED.stream().anyMatch(r -> r.contains("\"type\":\"PAYMENT\"") && r.contains("V-LOCAL-1")))
                .isTrue();
        String rejected = "E-REJ-" + UUID.randomUUID();
        api.expect(api.post("/v1/partner-states/" + partner + "/inbound-violations", inbound(rejected, 1), OFFICER),
                201);
        assertThat(exchange.violationRejected(partner, rejected, "VEHICLE_NOT_REGISTERED_HERE")).isTrue();

        // our fine is paid in the partner state: it closes here and the partner owes this state
        String ref = "V-OUT-P-" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode out = api.expect(api.post("/v1/partner-states/" + partner + "/violations", outbound(ref), OFFICER),
                201);
        String outExt = out.get("externalId").asString();
        assertThat(api.post("/v1/partner-states/" + partner + "/payment-notices", Map.of("externalId", outExt,
                "paymentReference", "PAY-1", "amountMinor", 1, "currency", "XTS"), OFFICER).getResponse().getStatus())
                .isEqualTo(400);
        JsonNode paid = api.expect(api.post("/v1/partner-states/" + partner + "/payment-notices", Map.of("externalId",
                outExt, "paymentReference", "PAY-1", "amountMinor", 50_000, "currency", "XTS"), OFFICER), 200);
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(api.expect(api.post("/v1/partner-states/" + partner + "/payment-notices", Map.of("externalId",
                outExt, "paymentReference", "PAY-1", "amountMinor", 50_000, "currency", "XTS"), OFFICER), 200)
                .get("id")).as("a repeat changes nothing").isEqualTo(paid.get("id"));

        Instant to = Instant.now().plusSeconds(1);
        JsonNode settlement = api.expect(api.get("/v1/partner-states/" + partner + "/settlements?from=" + from
                + "&to=" + to, OFFICER), 200).get("items").get(0);
        assertThat(settlement.get("collectedHereMinor").asLong()).isEqualTo(30_000);
        assertThat(settlement.get("commissionHereMinor").asLong()).as("10% kept here").isEqualTo(3_000);
        assertThat(settlement.get("owedToPartnerMinor").asLong()).isEqualTo(27_000);
        assertThat(settlement.get("collectedByPartnerMinor").asLong()).isEqualTo(50_000);
        assertThat(settlement.get("owedByPartnerMinor").asLong()).isEqualTo(45_000);
        assertThat(settlement.get("netMinor").asLong()).as("45 000 owed by the partner - 27 000 owed to it")
                .isEqualTo(18_000);
        assertThat(api.post("/v1/partner-states/" + partner + "/settlement-statements", Map.of("from", from.toString(),
                "to", Instant.now().plus(1, ChronoUnit.DAYS).toString()), OFFICER).getResponse().getStatus())
                .as("period has not ended").isEqualTo(400);
        Thread.sleep(1_100);
        JsonNode issued = api.expect(api.post("/v1/partner-states/" + partner + "/settlement-statements", Map.of("from",
                from.toString(), "to", to.toString()), OFFICER), 200).get("items").get(0);
        String statementId = issued.get("statement").get("id").asString();
        assertThat(api.expect(api.get("/v1/settlement-statements/" + statementId, OFFICER), 200).get("lines"))
                .hasSize(2);
        assertThat(api.expect(api.get("/v1/partner-states/" + partner + "/settlement-statements", OFFICER), 200)
                .get("items")).hasSize(1);
        assertThat(api.expect(api.post("/v1/partner-states/" + partner + "/settlement-statements", Map.of("from",
                from.toString(), "to", to.toString()), OFFICER), 200).get("items")).as("nothing left unsettled")
                .isEmpty();
    }

    @Test
    @Tag("XB-005")
    @DisplayName("XB-005: objections go to the issuing state and its decision applies; a partner's objection to a "
            + "violation sent from here is answered with the local decision")
    void objections() throws Exception {
        String partner = partner("REALTIME", false);
        String ext = "E-OBJ-" + UUID.randomUUID();
        api.expect(api.post("/v1/partner-states/" + partner + "/inbound-violations", inbound(ext, 10_000), OFFICER),
                201);
        Map<String, Object> objection = Map.of("externalId", ext, "reason", "car was sold before the date");
        assertThat(api.expect(api.post("/v1/partner-states/" + partner + "/objections", objection, OFFICER), 200)
                .get("status").asString()).isEqualTo("OBJECTED");
        assertThat(api.expect(api.post("/v1/partner-states/" + partner + "/objection-decisions", Map.of("externalId",
                ext, "decision", "REJECTED", "reason", "photo is clear"), OFFICER), 200).get("status").asString())
                .isEqualTo("RECEIVED");
        assertThat(api.post("/v1/partner-states/" + partner + "/objection-decisions", Map.of("externalId", ext,
                "decision", "REJECTED"), OFFICER).getResponse().getStatus()).as("nothing pending").isEqualTo(409);
        api.expect(api.post("/v1/partner-states/" + partner + "/objections", objection, OFFICER), 200);
        assertThat(api.expect(api.post("/v1/partner-states/" + partner + "/objection-decisions", Map.of("externalId",
                ext, "decision", "ACCEPTED"), OFFICER), 200).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(api.post("/v1/partner-states/" + partner + "/objection-decisions", Map.of("externalId", ext,
                "decision", "MAYBE"), OFFICER).getResponse().getStatus()).isEqualTo(400);

        String ref = "V-PO-" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode out = api.expect(api.post("/v1/partner-states/" + partner + "/violations", outbound(ref), OFFICER),
                201);
        assertThat(api.post("/v1/partner-states/" + partner + "/objections", Map.of("violationNumber", ref,
                "reason", "x"), OFFICER).getResponse().getStatus()).as("not a partner violation").isEqualTo(400);
        assertThat(api.expect(api.post("/v1/partner-states/" + partner + "/inbound-objections", Map.of("externalId",
                out.get("externalId").asString(), "reason", "driver disputes"), OFFICER), 200).get("status")
                .asString()).isEqualTo("OBJECTED");
        assertThat(exchange.localObjectionDecided(ref, "ACCEPT", "evidence accepted")).isPresent();
        assertThat(status(out.get("id").asString())).isEqualTo("CANCELLED");
        assertThat(api.post("/v1/partner-states/" + partner + "/inbound-objections", Map.of("externalId",
                "NOPE", "reason", "x"), OFFICER).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @Tag("XB-006")
    @Tag("DL-064")
    @DisplayName("XB-006, DL-064: accident, permit and licence data go both ways only where the agreement allows")
    void dataExchange() throws Exception {
        String open = partner("REALTIME", true);
        String closed = partner("REALTIME", false);
        Map<String, Object> licence = Map.of("type", "LICENCE", "reference", "DL-123", "content", Map.of(
                "licenceNumber", "LX000123", "classes", "LIGHT", "valid", true));
        assertThat(api.expect(api.post("/v1/partner-states/" + open + "/data-exchanges", licence, OFFICER), 201)
                .get("type").asString()).isEqualTo("LICENCE");
        assertThat(api.post("/v1/partner-states/" + closed + "/data-exchanges", licence, OFFICER).getResponse()
                .getStatus()).isEqualTo(409);
        assertThat(api.post("/v1/partner-states/" + open + "/data-exchanges", Map.of("type", "WEATHER", "reference",
                "W1", "content", Map.of("a", 1)), OFFICER).getResponse().getStatus()).isEqualTo(400);
        String ext = "D-" + UUID.randomUUID();
        Map<String, Object> accident = Map.of("externalId", ext, "type", "ACCIDENT", "reference", "ACC-9",
                "content", Map.of("reportNumber", "ACC-9", "injuries", 2));
        JsonNode received = api.expect(api.post("/v1/partner-states/" + open + "/inbound-data", accident, OFFICER), 201);
        assertThat(api.expect(api.post("/v1/partner-states/" + open + "/inbound-data", accident, OFFICER), 201)
                .get("id")).isEqualTo(received.get("id"));
        assertThat(db.sql("select count(*) from event_outbox where event_type = 'itms.crossborder.data-received'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(1);
        assertThat(api.expect(api.get("/v1/exchange-messages?partner=" + open + "&limit=50", OFFICER), 200)
                .get("items").size()).isGreaterThanOrEqualTo(2);
        assertThat(api.get("/v1/exchange-messages/" + UUID.randomUUID(), OFFICER).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(api.post("/v1/partner-states/" + open + "/data-exchanges", licence, MAKER).getResponse()
                .getStatus()).isEqualTo(403);
    }
}

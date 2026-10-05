package gov.itms.transaction.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A real HTTP stand-in for the identity provider's token endpoint and hold-eligibility-service's
 * {@code POST /v1/eligibility-checks} (CC-032, ADR-034), so integration tests exercise the client-credentials call
 * end to end. A subject id starting with {@code BLOCKED} is blocked (one disclosed and one masked reason), one
 * starting with {@code DOWN} makes the service answer 503; everything else is eligible.
 */
final class EligibilityStub {

    static final String CLIENT_ID = "itms-transaction-service";
    static final String CLIENT_SECRET = "it-secret";
    static final String TOKEN = "it-service-token";

    /** One eligibility request as received. */
    record Received(String authorisation, String channel, String language, String correlationId, JsonNode body) {
    }

    final List<Received> received = new CopyOnWriteArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;

    EligibilityStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/token", this::token);
        server.createContext("/v1/eligibility-checks", this::check);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void token(HttpExchange exchange) throws IOException {
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                || !"grant_type=client_credentials".equals(form)) {
            respond(exchange, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        respond(exchange, 200, "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":300}");
    }

    private void check(HttpExchange exchange) throws IOException {
        JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
        var headers = exchange.getRequestHeaders();
        received.add(new Received(headers.getFirst("Authorization"), headers.getFirst("X-Channel"),
                headers.getFirst("Accept-Language"), headers.getFirst("X-Correlation-Id"), body));
        if (!("Bearer " + TOKEN).equals(headers.getFirst("Authorization"))) {
            respond(exchange, 401, "");
            return;
        }
        boolean blocked = false;
        for (JsonNode subject : body.get("subjects")) {
            String id = subject.get("id").asString();
            if (id.startsWith("DOWN")) {
                respond(exchange, 503, "{\"status\":503,\"code\":\"INTERNAL_ERROR\"}");
                return;
            }
            blocked |= id.startsWith("BLOCKED");
        }
        boolean arabic = "ar".equals(headers.getFirst("Accept-Language"));
        String blocks = !blocked ? "[]" : """
                [{"reasonCode":"UNPAID_FINES","nextStep":"PAY_FINES","message":"%s"},
                 {"reasonCode":"RESTRICTED","nextStep":"CONTACT_AUTHORITY","message":"%s"}]""".formatted(
                arabic ? "توجد مخالفات غير مدفوعة." : "There are unpaid fines. Pay them, then try again.",
                arabic ? "تواصل مع الهيئة." : "A restriction applies. Contact the Authority.");
        respond(exchange, 200, "{\"serviceCode\":\"" + body.get("serviceCode").asString()
                + "\",\"checkedOn\":\"2026-10-05\",\"eligible\":" + !blocked + ",\"blocks\":" + blocks + "}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", status < 400 || body.isEmpty()
                ? "application/json" : "application/problem+json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}

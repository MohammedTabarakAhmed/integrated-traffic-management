package gov.itms.violation.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real HTTP stand-ins for violation-service's partners: the identity provider's token endpoint, payment-service
 * payables (register and cancel) and hold-eligibility-service holds. Received bodies and Idempotency-Keys are kept for
 * the test; {@link #holdsDown} makes the hold service answer 503.
 */
public final class PartnerStub implements AutoCloseable {

    public static final String CLIENT_ID = "itms-violation-service";
    public static final String CLIENT_SECRET = "it-secret";

    public final List<JsonNode> payables = new CopyOnWriteArrayList<>();
    public final List<String> payableKeys = new CopyOnWriteArrayList<>();
    public final List<String> cancelledPayables = new CopyOnWriteArrayList<>();
    public final List<JsonNode> holds = new CopyOnWriteArrayList<>();
    public final List<String> holdKeys = new CopyOnWriteArrayList<>();
    public volatile boolean holdsDown;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;

    public PartnerStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/token", ex -> respond(ex, 200,
                "{\"access_token\":\"it-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
        server.createContext("/v1/payables", this::payables);
        server.createContext("/v1/holds", ex -> {
            JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
            if (holdsDown) {
                respond(ex, 503, "{}");
                return;
            }
            holds.add(body);
            holdKeys.add(String.valueOf(ex.getRequestHeaders().getFirst("Idempotency-Key")));
            respond(ex, 201, "{\"id\":\"" + UUID.randomUUID() + "\"}");
        });
        server.start();
    }

    private void payables(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (path.endsWith("/cancellations")) {
            cancelledPayables.add(path.split("/")[3]);
            respond(ex, 200, "{}");
            return;
        }
        payables.add(json.readTree(raw));
        payableKeys.add(String.valueOf(ex.getRequestHeaders().getFirst("Idempotency-Key")));
        respond(ex, 201, "{\"id\":\"" + UUID.randomUUID() + "\"}");
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

package gov.itms.licensing.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real HTTP stand-ins for licensing-service's partners: the identity provider's token endpoint, fees-service quotes,
 * payment-service payables, hold-eligibility-service checks and traffic-file-service files. Received bodies are kept for
 * the test; a traffic file in {@link #blockedFiles} is blocked by a hold; {@link #feesDown} makes fees-service answer
 * 503. A traffic file has the type and residency end in {@link #files} (default CITIZEN, no residency); one in
 * {@link #unknownFiles} answers 404; {@link #trafficFilesDown} makes traffic-file-service answer 503.
 */
public final class PartnerStub implements AutoCloseable {

    public static final String CLIENT_ID = "itms-licensing-service";
    public static final String CLIENT_SECRET = "it-secret";
    public static final long FEE = 30_000;

    public final List<JsonNode> quotes = new CopyOnWriteArrayList<>();
    public final List<JsonNode> payables = new CopyOnWriteArrayList<>();
    public final List<String> payableKeys = new CopyOnWriteArrayList<>();
    public final List<JsonNode> checks = new CopyOnWriteArrayList<>();
    public final Set<String> blockedFiles = ConcurrentHashMap.newKeySet();
    public volatile boolean feesDown;
    public final Map<String, String[]> files = new ConcurrentHashMap<>();
    public final Set<String> unknownFiles = ConcurrentHashMap.newKeySet();
    public volatile boolean trafficFilesDown;
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
        server.createContext("/v1/quotes", ex -> {
            JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
            if (feesDown) {
                respond(ex, 503, "{}");
                return;
            }
            quotes.add(body);
            respond(ex, 201, "{\"id\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"financialItem\":\"REV_LICENCE\","
                    + "\"beneficiary\":\"AUTHORITY\",\"nameEn\":\"Licence\",\"amount\":" + FEE + "}],\"total\":"
                    + FEE + ",\"currency\":\"XTS\"}");
        });
        server.createContext("/v1/payables", ex -> {
            payables.add(json.readTree(ex.getRequestBody().readAllBytes()));
            payableKeys.add(String.valueOf(ex.getRequestHeaders().getFirst("Idempotency-Key")));
            respond(ex, 201, "{\"id\":\"" + UUID.randomUUID() + "\"}");
        });
        server.createContext("/v1/eligibility-checks", this::eligibility);
        server.createContext("/v1/traffic-files/", this::trafficFile);
        server.start();
    }

    private void trafficFile(HttpExchange ex) throws IOException {
        String number = ex.getRequestURI().getPath().substring("/v1/traffic-files/".length());
        if (trafficFilesDown) {
            respond(ex, 503, "{}");
            return;
        }
        if (unknownFiles.contains(number)) {
            respond(ex, 404, "{}");
            return;
        }
        String[] f = files.getOrDefault(number, new String[] {"CITIZEN", null});
        respond(ex, 200, "{\"fileNumber\":\"" + number + "\",\"kind\":\"INDIVIDUAL\",\"category\":\"INDIVIDUAL\","
                + "\"type\":\"" + f[0] + "\",\"status\":\"ACTIVE\",\"identity\":{\"residencyExpiry\":"
                + (f[1] == null ? "null" : "\"" + f[1] + "\"") + "}}");
    }

    private void eligibility(HttpExchange ex) throws IOException {
        JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
        checks.add(body);
        String service = body.get("serviceCode").asString();
        boolean blocked = false;
        for (JsonNode s : body.get("subjects")) {
            blocked |= blockedFiles.contains(s.get("id").asString());
        }
        respond(ex, 200, blocked
                ? "{\"serviceCode\":\"" + service + "\",\"eligible\":false,\"blocks\":[{\"reasonCode\":\"UNPAID_FINES\","
                + "\"nextStep\":\"PAY_FINES\",\"message\":\"Pay your outstanding fines first.\"}]}"
                : "{\"serviceCode\":\"" + service + "\",\"eligible\":true,\"blocks\":[]}");
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

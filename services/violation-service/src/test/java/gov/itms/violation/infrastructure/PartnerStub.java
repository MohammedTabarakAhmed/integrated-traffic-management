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
    /** Payable holds and lifts as "HOLD id sourceRef" / "LIFT id sourceRef" (VF-052). */
    public final List<String> payableHolds = new CopyOnWriteArrayList<>();
    /** vehicle-service: plate to {vin, ownerFileNumber, region}; other plates answer 404 (XB-002). */
    public final java.util.Map<String, String[]> vehicles = new java.util.concurrent.ConcurrentHashMap<>();
    /** rental-service stand-in (TR-032): plate -> {contractId, companyFile, renterFile or null}, covering any time. */
    public final java.util.Map<String, String[]> rentals = new java.util.concurrent.ConcurrentHashMap<>();
    public final List<String> rentalQueries = new CopyOnWriteArrayList<>();
    public final List<JsonNode> holds = new CopyOnWriteArrayList<>();
    public final List<String> holdKeys = new CopyOnWriteArrayList<>();
    public volatile boolean holdsDown;
    /** Lifted hold ids; a hold id starting with {@code GONE} answers 409 HOLD_NOT_ACTIVE. */
    public final List<String> lifts = new CopyOnWriteArrayList<>();
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
        server.createContext("/v1/vehicles", ex -> {
            String query = java.net.URLDecoder.decode(String.valueOf(ex.getRequestURI().getRawQuery()),
                    StandardCharsets.UTF_8);
            String[] v = vehicles.get(query.startsWith("plate=") ? query.substring(6) : "");
            if (v == null) {
                respond(ex, 404, "{\"code\":\"VEHICLE_NOT_FOUND\"}");
                return;
            }
            respond(ex, 200, "{\"vin\":\"" + v[0] + "\",\"ownerFileNumber\":\"" + v[1] + "\",\"region\":\""
                    + v[2] + "\"}");
        });
        server.createContext("/v1/rental-contracts/coverage", ex -> {
            java.util.Map<String, String> q = new java.util.HashMap<>();
            for (String pair : String.valueOf(ex.getRequestURI().getRawQuery()).split("&")) {
                String[] kv = pair.split("=", 2);
                q.put(kv[0], kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
            rentalQueries.add(q.get("plate") + "@" + q.get("at"));
            String[] r = rentals.get(String.valueOf(q.get("plate")));
            if (r == null) {
                respond(ex, 200, "{\"covered\":false,\"contractId\":null,\"companyFile\":null,\"renterFile\":null,"
                        + "\"visitor\":false}");
                return;
            }
            respond(ex, 200, "{\"covered\":true,\"contractId\":\"" + r[0] + "\",\"companyFile\":\"" + r[1]
                    + "\",\"renterFile\":" + (r[2] == null ? "null" : "\"" + r[2] + "\"") + ",\"visitor\":"
                    + (r[2] == null) + "}");
        });
        server.createContext("/v1/holds", ex -> {
            JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
            if (holdsDown) {
                respond(ex, 503, "{}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/lifts")) {
                String id = path.substring("/v1/holds/".length(), path.length() - "/lifts".length());
                if (id.startsWith("GONE")) {
                    respond(ex, 409, "{\"code\":\"HOLD_NOT_ACTIVE\"}");
                    return;
                }
                lifts.add(id + " " + body.get("reason").asString());
                respond(ex, 200, "{\"id\":\"" + id + "\",\"status\":\"LIFTED\"}");
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
        if (path.endsWith("/holds") || path.endsWith("/hold-lifts")) {
            JsonNode body = json.readTree(raw);
            payableHolds.add((path.endsWith("/holds") ? "HOLD " : "LIFT ") + path.split("/")[3] + " "
                    + body.get("sourceRef").asString());
            respond(ex, 204, "");
            return;
        }
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
        if (bytes.length == 0) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
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

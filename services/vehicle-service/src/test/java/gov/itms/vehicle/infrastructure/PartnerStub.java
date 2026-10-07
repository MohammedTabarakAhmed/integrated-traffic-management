package gov.itms.vehicle.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real HTTP stand-ins for vehicle-service's partners: the identity provider's token endpoint, the insurance hub
 * (ICD-02 {@code GET /v1/policies}), fees-service quotes and payment-service payables. A VIN's policy end date is set
 * by the test ({@link #policyEnds}); a VIN in {@link #insurerDown} makes the hub answer 503; a VIN without a policy
 * gets 404.
 */
public final class PartnerStub implements AutoCloseable {

    public static final String CLIENT_ID = "itms-vehicle-service";
    public static final String CLIENT_SECRET = "it-secret";

    public final Map<String, LocalDate> policyEnds = new ConcurrentHashMap<>();
    public final List<String> insurerDown = new CopyOnWriteArrayList<>();
    public final List<JsonNode> quotes = new CopyOnWriteArrayList<>();
    public final List<JsonNode> payables = new CopyOnWriteArrayList<>();
    public final List<String> payableKeys = new CopyOnWriteArrayList<>();
    public final List<String> authorisations = new CopyOnWriteArrayList<>();
    public volatile long feeAmount = 40000;
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
        server.createContext("/v1/policies", this::policies);
        server.createContext("/v1/quotes", ex -> {
            JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
            quotes.add(body);
            authorisations.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            respond(ex, 201, "{\"id\":\"Q-" + UUID.randomUUID() + "\",\"lines\":[{\"financialItem\":\"REG_FEE\","
                    + "\"beneficiary\":\"AUTHORITY\",\"nameEn\":\"Registration fee\",\"amount\":" + feeAmount
                    + "},{\"financialItem\":\"ZERO\",\"beneficiary\":\"AUTHORITY\",\"nameEn\":\"Waived\",\"amount\":0}],"
                    + "\"total\":" + feeAmount + ",\"currency\":\"XTS\"}");
        });
        server.createContext("/v1/payables", ex -> {
            payables.add(json.readTree(ex.getRequestBody().readAllBytes()));
            payableKeys.add(String.valueOf(ex.getRequestHeaders().getFirst("Idempotency-Key")));
            respond(ex, 201, "{\"id\":\"" + UUID.randomUUID() + "\"}");
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void policies(HttpExchange ex) throws IOException {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2);
                q.put(kv[0], kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        String vin = q.get("vin");
        if (insurerDown.contains(vin)) {
            respond(ex, 503, "{}");
            return;
        }
        LocalDate end = policyEnds.get(vin);
        if (end == null) {
            respond(ex, 404, "{}");
            return;
        }
        respond(ex, 200, "{\"items\":[{\"policyNo\":\"POL-" + vin + "\",\"insurerCode\":\"INS1\",\"vin\":\"" + vin
                + "\",\"coverType\":\"COMPREHENSIVE\",\"validFrom\":\"" + end.minusYears(1) + "\",\"validTo\":\""
                + end + "\",\"status\":\"ACTIVE\"},{\"policyNo\":\"OTHER\",\"insurerCode\":\"INS1\",\"vin\":"
                + "\"OTHERVIN\",\"coverType\":\"TPL\",\"validFrom\":\"2000-01-01\",\"validTo\":\"2999-01-01\","
                + "\"status\":\"ACTIVE\"}]}");
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

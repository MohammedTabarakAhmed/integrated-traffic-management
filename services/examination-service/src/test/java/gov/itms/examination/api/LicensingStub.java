package gov.itms.examination.api;

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

/** Real HTTP stand-in for licensing-service's examination transfers (DL-036) and the token endpoint. */
final class LicensingStub {

    record Seen(String idempotencyKey, String authorization, String body) {
    }

    final List<Seen> transfers = new CopyOnWriteArrayList<>();
    volatile boolean failing;
    private final HttpServer server;

    LicensingStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/token", ex -> respond(ex, 200,
                "{\"access_token\":\"it-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
        server.createContext("/v1/examination-transfers", this::transfer);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    private void transfer(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (failing) {
            respond(ex, 503, "{}");
            return;
        }
        transfers.add(new Seen(ex.getRequestHeaders().getFirst("Idempotency-Key"),
                ex.getRequestHeaders().getFirst("Authorization"), body));
        respond(ex, 201, "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"TRANSFERRED\"}");
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}

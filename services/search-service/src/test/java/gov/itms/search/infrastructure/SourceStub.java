package gov.itms.search.infrastructure;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real HTTP stand-in for the owning services: answers per request path and query ({@link #answers}, status and body),
 * 404 otherwise, and keeps every request (method, path with query, authorization, channel, body).
 */
public final class SourceStub implements AutoCloseable {

    public record Seen(String method, String target, String authorization, String channel, String body) {
    }

    public record Answer(int status, String body, long delayMillis) {
    }

    public final Map<String, Answer> answers = new ConcurrentHashMap<>();
    public final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    public SourceStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            String target = ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            seen.add(new Seen(ex.getRequestMethod(), target, ex.getRequestHeaders().getFirst("Authorization"),
                    ex.getRequestHeaders().getFirst("X-Channel"),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Answer a = answers.getOrDefault(target, new Answer(404, "{}", 0));
            if (a.delayMillis() > 0) {
                try {
                    Thread.sleep(a.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = a.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(a.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bytes);
                }
            }
            ex.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

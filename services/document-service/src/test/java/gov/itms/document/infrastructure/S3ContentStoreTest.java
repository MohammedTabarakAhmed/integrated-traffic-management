package gov.itms.document.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import gov.itms.document.application.Ports;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-080")
@Tag("CC-085")
class S3ContentStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneOffset.UTC);
    private static final String ACCESS = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String HELLO_SHA = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    private static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static HttpRequest signed(S3ContentStore store, String method, String key, String hash, String type)
            throws Exception {
        Method m = S3ContentStore.class.getDeclaredMethod("signed", String.class, String.class, String.class,
                String.class, HttpRequest.BodyPublisher.class);
        m.setAccessible(true);
        return (HttpRequest) m.invoke(store, method, key, hash, type, HttpRequest.BodyPublishers.noBody());
    }

    @Test
    @DisplayName("ADR-005: SigV4 signatures match values computed independently (known answers), keys path-encoded")
    void sigV4KnownAnswers() throws Exception {
        S3ContentStore store = new S3ContentStore(URI.create("http://minio.test:9000"), "itms-documents", "local",
                ACCESS, SECRET, Duration.ofSeconds(5), CLOCK);
        HttpRequest put = signed(store, "PUT", "documents/abc d", HELLO_SHA, "text/plain");
        assertThat(put.uri().toString()).isEqualTo("http://minio.test:9000/itms-documents/documents/abc%20d");
        assertThat(put.headers().firstValue("x-amz-date")).contains("20261006T080000Z");
        assertThat(put.headers().firstValue("x-amz-content-sha256")).contains(HELLO_SHA);
        assertThat(put.headers().firstValue("Content-Type")).contains("text/plain");
        assertThat(put.headers().firstValue("Authorization")).contains("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"
                + "20261006/local/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature="
                + "1e3481206f495b93615618b6a466ffcf9d0e22bbe33d823126ef5c8557693cb8");

        HttpRequest get = signed(store, "GET", "documents/abc d", EMPTY_SHA, null);
        assertThat(get.headers().firstValue("Authorization").orElseThrow())
                .endsWith("Signature=e847c94196be34b026263c9e802eef4258fd6f203c12d85aaab265f2162a5d42");
        assertThat(get.headers().firstValue("Content-Type")).isEmpty();
    }

    @Test
    @DisplayName("CC-080: put then get returns the same bytes; a non-2xx answer or no server is unavailable")
    void roundTripAndErrors() throws Exception {
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            boolean signedOk = exchange.getRequestHeaders().getFirst("Authorization") != null
                    && exchange.getRequestHeaders().getFirst("x-amz-content-sha256") != null;
            if (!signedOk) {
                exchange.sendResponseHeaders(403, -1);
            } else if (exchange.getRequestMethod().equals("PUT")) {
                objects.put(path, exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(200, -1);
            } else if (objects.containsKey(path)) {
                byte[] body = objects.get(path);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            S3ContentStore store = new S3ContentStore(endpoint, "itms-documents", "local", ACCESS, SECRET,
                    Duration.ofSeconds(5), CLOCK);
            store.put("certificates/CRT-1", "hello".getBytes(StandardCharsets.UTF_8), "text/html", HELLO_SHA);
            assertThat(objects).containsKey("/itms-documents/certificates/CRT-1");
            try (InputStream in = store.get("certificates/CRT-1")) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
            }
            assertThatThrownBy(() -> store.get("documents/missing"))
                    .isInstanceOf(Ports.StoreUnavailableException.class).hasMessageContaining("404");
        } finally {
            server.stop(0);
        }
        S3ContentStore nobody = new S3ContentStore(URI.create("http://127.0.0.1:1"), "itms-documents", "local",
                ACCESS, SECRET, Duration.ofSeconds(1), CLOCK);
        assertThatThrownBy(() -> nobody.get("x")).isInstanceOf(Ports.StoreUnavailableException.class);
    }

    @Test
    @DisplayName("CC-080: an upload file that vanished is reported as unavailable, not as a crash")
    void missingFile() {
        S3ContentStore store = new S3ContentStore(URI.create("http://127.0.0.1:1"), "itms-documents", "local",
                ACCESS, SECRET, Duration.ofSeconds(1), CLOCK);
        assertThatThrownBy(() -> store.put("k", java.nio.file.Path.of("does-not-exist.bin"), "application/pdf",
                HELLO_SHA)).isInstanceOf(Ports.StoreUnavailableException.class);
    }
}

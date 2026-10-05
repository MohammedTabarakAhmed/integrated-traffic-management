package gov.itms.hold.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The observability endpoints on real ports (NFR-040, ADR-037): probes and the Prometheus scrape are served on the
 * separate management port without a token; the API port does not serve them, and no other actuator endpoint is
 * open. Log records are sent with the trace of the request that wrote them. The settings are the service's own
 * {@code application.yml}; only the ports are random, and log export is switched on with an unreachable collector
 * plus an in-memory exporter that records what would be sent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.opentelemetry.logging.export.otlp.endpoint=http://127.0.0.1:9/v1/logs",
        "management.opentelemetry.logging.export.schedule-delay=100ms",
        "logging.level.org.springframework.security.web.FilterChainProxy=DEBUG", // a log line inside each request
        "itms.security.issuer=https://idp.test/realms/itms",
        "itms.security.jwk-set-uri=https://idp.test/realms/itms/protocol/openid-connect/certs",
        "itms.security.authorisation-source=token-claims"})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT {

    /** Records the log records the service sends to the collector. */
    static final class RecordingLogExporter implements LogRecordExporter {

        final List<LogRecordData> sent = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<LogRecordData> records) {
            sent.addAll(records);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LogCapture {

        @Bean
        RecordingLogExporter recordingLogExporter() {
            return new RecordingLogExporter();
        }
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int apiPort;
    @LocalManagementPort
    int managementPort;
    @Autowired
    RecordingLogExporter logs;

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Tag("NFR-040")
    @DisplayName("NFR-040: the management port serves the Prometheus scrape and the probes without a token")
    void managementPortServesMetricsAndProbes() throws Exception {
        assertThat(managementPort).isNotEqualTo(apiPort);
        // Metrics are pulled, never pushed (ADR-037): the starter's OTLP meter registry is excluded.
        assertThat(ClassUtils.isPresent("io.micrometer.registry.otlp.OtlpMeterRegistry", null)).isFalse();
        assertThat(get(apiPort, "/v1/holds?subjectType=VEHICLE&subjectId=X").statusCode()).isEqualTo(401);

        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/plain"));
        assertThat(scrape.body())
                .contains("application=\"hold-eligibility-service\"")
                .contains("http_server_requests_seconds_bucket") // histograms for latency alerts (NFR-044)
                .contains("status=\"401\"")
                .contains("jvm_memory_used_bytes")
                .contains("jdbc_connections_max");

        HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"UP\"");
        assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health").body()).doesNotContain("components");
    }

    @Test
    @Tag("NFR-040")
    @Tag("SEC-022")
    @DisplayName("NFR-040: the API port does not serve the actuator, and nothing else is open on the management port")
    void nothingElseIsOpen() throws Exception {
        assertThat(get(apiPort, "/actuator/prometheus").statusCode()).isEqualTo(401);
        assertThat(get(apiPort, "/actuator/loggers").statusCode()).isEqualTo(401);
        assertThat(get(apiPort, "/actuator/health").statusCode()).isEqualTo(404);

        for (String path : new String[] {"/actuator", "/actuator/loggers", "/actuator/env", "/actuator/info",
                "/actuator/heapdump", "/actuator/configprops", "/v1/holds"}) {
            assertThat(get(managementPort, path).statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    @Tag("NFR-040")
    @DisplayName("NFR-040: a request's log lines carry its trace, on the console and in the records sent centrally")
    void logsCarryTheTrace(CapturedOutput console) throws Exception {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + apiPort + "/v1/holds?subjectType=VEHICLE&subjectId=OBS"))
                .header("traceparent", "00-" + traceId + "-b7ad6b7169203331-01")
                .header("X-Correlation-Id", "obs-it-12345").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(401);

        long deadline = System.nanoTime() + 10_000_000_000L;
        List<LogRecordData> traced = List.of();
        while (traced.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
            traced = logs.sent.stream().filter(r -> traceId.equals(r.getSpanContext().getTraceId())).toList();
        }
        assertThat(traced).as("log records of the traced request").isNotEmpty();
        LogRecordData record = traced.getFirst();
        assertThat(record.getBodyValue().asString()).contains("/v1/holds");
        assertThat(record.getResource().getAttributes().asMap().toString()).contains("hold-eligibility-service");
        assertThat(record.getAttributes().asMap().toString()).contains("obs-it-12345");
        assertThat(console.getOut()).contains("[" + traceId + ",");
    }
}

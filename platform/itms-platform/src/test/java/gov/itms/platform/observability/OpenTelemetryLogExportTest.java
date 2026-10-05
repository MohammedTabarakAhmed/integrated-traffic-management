package gov.itms.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class OpenTelemetryLogExportTest {

    @Test
    @Tag("NFR-040")
    @DisplayName("NFR-040: while running, application logs reach the central log exporter; after stop they do not")
    void attachesWhileRunning() {
        InMemoryLogRecordExporter exported = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider loggers = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exported)).build()) {
            OpenTelemetryLogExport export = new OpenTelemetryLogExport(loggers);
            org.slf4j.Logger log = LoggerFactory.getLogger("gov.itms.test.Export");
            Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);

            export.start();
            assertThat(export.isRunning()).isTrue();
            assertThat(root.getAppender("ITMS_OTEL")).isNotNull();
            log.info("exported {}", 1);

            export.stop();
            assertThat(export.isRunning()).isFalse();
            assertThat(root.getAppender("ITMS_OTEL")).isNull();
            log.info("not exported");
            export.stop(); // stopping twice is harmless

            assertThat(exported.getFinishedLogRecordItems())
                    .extracting(r -> r.getBodyValue().asString()).containsExactly("exported 1");
        }
    }
}

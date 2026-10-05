package gov.itms.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import gov.itms.platform.web.CorrelationIdFilter;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class OpenTelemetryLogAppenderTest {

    private final InMemoryLogRecordExporter exported = InMemoryLogRecordExporter.create();
    private final SdkLoggerProvider loggers = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exported)).build();
    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    @AfterEach
    void close() {
        loggers.close();
        MDC.clear();
    }

    private OpenTelemetryLogAppender appender() {
        OpenTelemetryLogAppender appender = new OpenTelemetryLogAppender(loggers);
        appender.setContext(context);
        appender.start();
        return appender;
    }

    private LoggingEvent event(String logger, Level level, String message, Throwable thrown, Object... args) {
        Logger source = context.getLogger(logger);
        return new LoggingEvent(Logger.class.getName(), source, level, message, thrown, args);
    }

    @Test
    @Tag("NFR-040")
    @DisplayName("NFR-040: a log event is sent with its message, level, logger, thread, correlation id and trace")
    void sendsTheEventWithItsContext() {
        OpenTelemetryLogAppender appender = appender();
        SdkTracerProvider tracing = SdkTracerProvider.builder().build();
        Span span = tracing.get("test").spanBuilder("request").startSpan();
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-12345678");
        try (Scope ignored = span.makeCurrent()) {
            appender.doAppend(event("gov.itms.iam.Example", Level.WARN, "Refused {} of {}", null, "role", "u-1"));
        } finally {
            span.end();
            tracing.close();
        }

        List<LogRecordData> records = exported.getFinishedLogRecordItems();
        assertThat(records).hasSize(1);
        LogRecordData record = records.getFirst();
        assertThat(record.getBodyValue().asString()).isEqualTo("Refused role of u-1");
        assertThat(record.getSeverity()).isEqualTo(Severity.WARN);
        assertThat(record.getSeverityText()).isEqualTo("WARN");
        assertThat(record.getInstrumentationScopeInfo().getName()).isEqualTo("gov.itms.iam.Example");
        assertThat(record.getAttributes().get(OpenTelemetryLogAppender.CORRELATION_ID)).isEqualTo("corr-12345678");
        assertThat(record.getAttributes().get(OpenTelemetryLogAppender.THREAD_NAME))
                .isEqualTo(Thread.currentThread().getName());
        assertThat(record.getSpanContext().getTraceId()).isEqualTo(span.getSpanContext().getTraceId());
        assertThat(record.getSpanContext().getSpanId()).isEqualTo(span.getSpanContext().getSpanId());
        assertThat(record.getTimestampEpochNanos()).isPositive();
    }

    @Test
    @Tag("NFR-040")
    @DisplayName("NFR-040: an exception is sent as type, message and stack trace; no correlation id outside requests")
    void sendsTheException() {
        appender().doAppend(event("gov.itms.Job", Level.ERROR, "Job failed", new IllegalStateException("broken")));

        LogRecordData record = exported.getFinishedLogRecordItems().getFirst();
        assertThat(record.getSeverity()).isEqualTo(Severity.ERROR);
        assertThat(record.getAttributes().get(OpenTelemetryLogAppender.EXCEPTION_TYPE))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(record.getAttributes().get(OpenTelemetryLogAppender.EXCEPTION_MESSAGE)).isEqualTo("broken");
        assertThat(record.getAttributes().get(OpenTelemetryLogAppender.EXCEPTION_STACKTRACE))
                .contains("IllegalStateException: broken").contains("sendsTheException");
        assertThat(record.getAttributes().get(OpenTelemetryLogAppender.CORRELATION_ID)).isNull();
        assertThat(record.getSpanContext().isValid()).isFalse();
    }

    @Test
    @Tag("NFR-040")
    @DisplayName("NFR-040: the OpenTelemetry SDK's own log events are not sent back through it")
    void skipsTheSdksOwnEvents() {
        OpenTelemetryLogAppender appender = appender();
        appender.doAppend(event("io.opentelemetry.exporter.internal.http.HttpExporter", Level.WARN, "export failed",
                null));
        appender.doAppend(event("io.opentelemetry.sdk.logs", Level.ERROR, "queue full", null));

        assertThat(exported.getFinishedLogRecordItems()).isEmpty();
    }

    @Test
    @Tag("NFR-040")
    void mapsEveryLevel() {
        assertThat(Map.of(Level.ERROR, Severity.ERROR, Level.WARN, Severity.WARN, Level.INFO, Severity.INFO,
                Level.DEBUG, Severity.DEBUG, Level.TRACE, Severity.TRACE))
                .allSatisfy((level, severity) -> assertThat(OpenTelemetryLogAppender.severity(level))
                        .isEqualTo(severity));
        assertThat(OpenTelemetryLogAppender.severity(Level.OFF)).isEqualTo(Severity.UNDEFINED_SEVERITY_NUMBER);
    }
}

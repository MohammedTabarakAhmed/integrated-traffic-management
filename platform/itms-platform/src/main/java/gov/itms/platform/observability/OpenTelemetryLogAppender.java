package gov.itms.platform.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import gov.itms.platform.web.CorrelationIdFilter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import java.util.Map;

/**
 * Sends every log event to the central log store through OpenTelemetry (NFR-040, ADR-037), next to the console
 * output. The record carries the active trace and span (so a log line links to its trace), the correlation id, the
 * thread, and the exception if any; the instrumentation scope is the logger name. Events of the OpenTelemetry SDK
 * itself are not sent, so a failing export cannot feed itself.
 * <p>
 * What is logged is unchanged: no personal data or secrets may be logged in the first place (SEC-051).
 */
final class OpenTelemetryLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    static final AttributeKey<String> CORRELATION_ID = AttributeKey.stringKey("itms.correlation_id");
    static final AttributeKey<String> THREAD_NAME = AttributeKey.stringKey("thread.name");
    static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    private final LoggerProvider loggers;

    OpenTelemetryLogAppender(LoggerProvider loggers) {
        this.loggers = loggers;
        setName("ITMS_OTEL");
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event.getLoggerName().startsWith("io.opentelemetry")) {
            return;
        }
        LogRecordBuilder record = loggers.get(event.getLoggerName()).logRecordBuilder()
                .setTimestamp(event.getInstant())
                .setSeverity(severity(event.getLevel()))
                .setSeverityText(event.getLevel().toString())
                .setBody(event.getFormattedMessage())
                .setAttribute(THREAD_NAME, event.getThreadName());
        Map<String, String> mdc = event.getMDCPropertyMap();
        String correlationId = mdc == null ? null : mdc.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            record.setAttribute(CORRELATION_ID, correlationId);
        }
        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            record.setAttribute(EXCEPTION_TYPE, thrown.getClassName());
            if (thrown.getMessage() != null) {
                record.setAttribute(EXCEPTION_MESSAGE, thrown.getMessage());
            }
            record.setAttribute(EXCEPTION_STACKTRACE, ThrowableProxyUtil.asString(thrown));
        }
        record.emit(); // the current OpenTelemetry context gives the trace and span
    }

    static Severity severity(Level level) {
        return switch (level.toInt()) {
            case Level.ERROR_INT -> Severity.ERROR;
            case Level.WARN_INT -> Severity.WARN;
            case Level.INFO_INT -> Severity.INFO;
            case Level.DEBUG_INT -> Severity.DEBUG;
            case Level.TRACE_INT -> Severity.TRACE;
            default -> Severity.UNDEFINED_SEVERITY_NUMBER;
        };
    }
}

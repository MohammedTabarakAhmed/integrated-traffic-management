package gov.itms.platform.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.logs.LoggerProvider;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Attaches the {@link OpenTelemetryLogAppender} to the root logger while the application runs, and detaches it before
 * the OpenTelemetry SDK shuts down, so the last records are flushed and none is sent to a closed exporter.
 */
public final class OpenTelemetryLogExport implements SmartLifecycle {

    private final LoggerProvider loggers;
    private volatile OpenTelemetryLogAppender appender;

    public OpenTelemetryLogExport(LoggerProvider loggers) {
        this.loggers = loggers;
    }

    @Override
    public void start() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return; // not Logback (for example a test with another binding): nothing to attach to
        }
        OpenTelemetryLogAppender created = new OpenTelemetryLogAppender(loggers);
        created.setContext(context);
        created.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(created);
        appender = created;
    }

    @Override
    public void stop() {
        OpenTelemetryLogAppender attached = appender;
        if (attached != null && LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(attached);
            attached.stop();
        }
        appender = null;
    }

    @Override
    public boolean isRunning() {
        return appender != null;
    }
}

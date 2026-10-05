package gov.itms.platform.observability;

import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Central logs (NFR-040, ADR-037). Traces and metrics need no code here: Spring Boot's OpenTelemetry and Prometheus
 * support is configured by each service's {@code management.*} settings. Log records are sent only when an OTLP log
 * endpoint is configured ({@code MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT}); otherwise only the console
 * log is written.
 */
@AutoConfiguration(afterName = "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration")
@ConditionalOnClass({OpenTelemetry.class, ch.qos.logback.classic.LoggerContext.class})
public class ItmsObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnBean(OpenTelemetry.class)
    @ConditionalOnProperty("management.opentelemetry.logging.export.otlp.endpoint")
    OpenTelemetryLogExport itmsOpenTelemetryLogExport(OpenTelemetry openTelemetry) {
        return new OpenTelemetryLogExport(openTelemetry.getLogsBridge());
    }
}

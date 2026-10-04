package gov.itms.platform;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.audit.JdbcAuditRecorder;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ItmsSecurityAutoConfiguration;
import gov.itms.platform.web.CorrelationIdFilter;
import gov.itms.platform.web.JsonInputHardening;
import gov.itms.platform.web.ProblemHandler;
import gov.itms.platform.web.StrictEnumParameters;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires the platform building blocks into every service that depends on {@code itms-platform}. */
@AutoConfiguration(after = ItmsSecurityAutoConfiguration.class,
        afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration")
public class ItmsPlatformAutoConfiguration {

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> itmsCorrelationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    ProblemHandler itmsProblemHandler(MessageSource messageSource) {
        return new ProblemHandler(messageSource);
    }

    /** Request bodies are never silently coerced (SEC-022); see {@link JsonInputHardening}. */
    @Bean
    JsonMapperBuilderCustomizer itmsJsonInputHardening() {
        return JsonInputHardening.customizer();
    }

    /** Enum query and path parameters must be the exact name (SEC-022). */
    @Bean
    StrictEnumParameters itmsStrictEnumParameters() {
        return new StrictEnumParameters();
    }

    /**
     * UTC, ticking in microseconds: the precision PostgreSQL {@code timestamptz} stores. A server-generated time in a
     * response or an audit record (CC-120) therefore always equals the stored value.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock itmsClock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean
    @ConditionalOnMissingBean(AuditRecorder.class)
    @ConditionalOnBean(JdbcClient.class)
    AuditRecorder itmsAuditRecorder(JdbcClient jdbc, ObjectMapper json, CurrentActorProvider actors, Clock clock,
                                    @Value("${spring.application.name}") String serviceName) {
        return new JdbcAuditRecorder(jdbc, json, actors, clock, serviceName);
    }
}

package gov.itms.platform;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.audit.JdbcAuditRecorder;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ItmsSecurityAutoConfiguration;
import gov.itms.platform.web.CorrelationIdFilter;
import gov.itms.platform.web.ProblemHandler;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

    @Bean
    @ConditionalOnMissingBean
    Clock itmsClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(AuditRecorder.class)
    @ConditionalOnBean(JdbcClient.class)
    AuditRecorder itmsAuditRecorder(JdbcClient jdbc, ObjectMapper json, CurrentActorProvider actors, Clock clock,
                                    @Value("${spring.application.name}") String serviceName) {
        return new JdbcAuditRecorder(jdbc, json, actors, clock, serviceName);
    }
}

package gov.itms.fees.infrastructure;

import gov.itms.fees.application.FeeService;
import gov.itms.fees.application.QuoteService;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires the application layer to its JDBC adapter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeesProperties.class)
public class FeesServiceConfiguration {

    @Bean
    JdbcFeeRepository feeRepository(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcFeeRepository(jdbc, json);
    }

    @Bean
    FeeService feeService(JdbcFeeRepository fees, AuditRecorder audit, DomainEventRecorder events, Clock clock,
                          FeesProperties properties) {
        return new FeeService(fees, audit, events, clock, properties.currency());
    }

    @Bean
    QuoteService quoteService(JdbcFeeRepository fees, AuditRecorder audit, Clock clock, FeesProperties properties) {
        return new QuoteService(fees, audit, clock, properties.quoteValidity(), properties.currency());
    }
}

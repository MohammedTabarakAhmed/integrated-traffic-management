package gov.itms.hold.infrastructure;

import gov.itms.hold.application.EligibilityService;
import gov.itms.hold.application.HoldService;
import gov.itms.platform.audit.AuditRecorder;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires the application layer to its JDBC adapter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EligibilityProperties.class)
public class HoldServiceConfiguration {

    @Bean
    JdbcHoldRepository holdRepository(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcHoldRepository(jdbc, json);
    }

    @Bean
    HoldService holdService(JdbcHoldRepository holds, AuditRecorder audit, Clock clock) {
        return new HoldService(holds, audit, clock);
    }

    @Bean
    EligibilityService eligibilityService(JdbcHoldRepository holds, EligibilityProperties properties,
                                          MessageSource messages, AuditRecorder audit, Clock clock) {
        return new EligibilityService(holds, properties.toPolicy(messages), audit, clock);
    }
}

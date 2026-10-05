package gov.itms.hold.infrastructure;

import gov.itms.hold.application.HoldService;
import gov.itms.platform.audit.AuditRecorder;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires the application layer to its JDBC adapter. */
@Configuration(proxyBeanMethods = false)
public class HoldServiceConfiguration {

    @Bean
    HoldService holdService(JdbcClient jdbc, ObjectMapper json, AuditRecorder audit, Clock clock) {
        return new HoldService(new JdbcHoldRepository(jdbc, json), audit, clock);
    }
}

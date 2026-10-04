package gov.itms.refdata.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.refdata.application.ReferenceDataService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires the application layer to its JDBC adapters. */
@Configuration(proxyBeanMethods = false)
public class ReferenceDataConfiguration {

    @Bean
    ReferenceDataService referenceDataService(JdbcClient jdbc, ObjectMapper json, AuditRecorder audit, Clock clock) {
        return new ReferenceDataService(new JdbcReferenceListRepository(jdbc),
                new JdbcReferenceItemRepository(jdbc, json), new JdbcChangeRequestRepository(jdbc, json), audit, clock);
    }
}

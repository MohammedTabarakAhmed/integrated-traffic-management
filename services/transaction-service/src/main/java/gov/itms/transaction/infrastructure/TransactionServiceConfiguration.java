package gov.itms.transaction.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.transaction.application.CatalogueService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires the application layer to its JDBC adapters. */
@Configuration(proxyBeanMethods = false)
public class TransactionServiceConfiguration {

    @Bean
    CatalogueService catalogueService(JdbcClient jdbc, ObjectMapper json, AuditRecorder audit, Clock clock) {
        return new CatalogueService(new JdbcServiceDefinitionRepository(jdbc), new JdbcAvailabilityRuleRepository(jdbc),
                new JdbcCatalogueChangeRepository(jdbc, json), audit, clock);
    }
}

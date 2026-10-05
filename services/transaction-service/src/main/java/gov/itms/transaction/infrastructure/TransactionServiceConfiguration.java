package gov.itms.transaction.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.transaction.application.CatalogueService;
import gov.itms.transaction.application.TransactionService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
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

    @Bean
    TransactionService transactionService(JdbcClient jdbc, AuditRecorder audit, Clock clock) {
        return new TransactionService(new JdbcServiceDefinitionRepository(jdbc),
                new JdbcAvailabilityRuleRepository(jdbc), new JdbcTransactionRepository(jdbc), audit, clock);
    }

    /** Draft expiry (CC-024, ADR-031); on by default, off with {@code itms.transaction.draft-expiry.enabled=false}. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(DraftExpiryProperties.class)
    @ConditionalOnProperty(name = "itms.transaction.draft-expiry.enabled", havingValue = "true", matchIfMissing = true)
    static class DraftExpiryConfiguration {

        @Bean
        DraftExpiryJob draftExpiryJob(TransactionService transactions, DraftExpiryProperties properties) {
            return new DraftExpiryJob(transactions, properties);
        }
    }
}

package gov.itms.transaction.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.client.ServiceClientProperties;
import gov.itms.platform.client.ServiceClients;
import gov.itms.transaction.application.CatalogueService;
import gov.itms.transaction.application.EligibilityChecker;
import gov.itms.transaction.application.TransactionService;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
    TransactionService transactionService(JdbcClient jdbc, EligibilityChecker eligibility, AuditRecorder audit,
                                          Clock clock) {
        return new TransactionService(new JdbcServiceDefinitionRepository(jdbc),
                new JdbcAvailabilityRuleRepository(jdbc), new JdbcTransactionRepository(jdbc), eligibility, audit,
                clock);
    }

    /**
     * Eligibility before submission (CC-032, ADR-034): hold-eligibility-service through its API, with this service's
     * own client-credentials account ({@code itms.clients.eligibility.*}); start-up fails if a setting is missing.
     */
    @Bean
    EligibilityChecker eligibilityChecker(Environment environment, Clock clock,
                                          ObjectProvider<ObservationRegistry> observations) {
        ServiceClientProperties properties = Binder.get(environment)
                .bind("itms.clients.eligibility", ServiceClientProperties.class)
                .orElseThrow(() -> new IllegalStateException("itms.clients.eligibility is not configured"))
                .validate("itms.clients.eligibility");
        return new RemoteEligibilityChecker(ServiceClients.create(properties, clock,
                observations.getIfAvailable(() -> ObservationRegistry.NOOP)));
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

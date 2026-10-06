package gov.itms.platform.events;

import gov.itms.platform.ItmsPlatformAutoConfiguration;
import gov.itms.platform.security.CurrentActorProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Domain events and the outbox relay (ADR-002, ADR-040, INT-005). Every service can record events; they are sent
 * to the broker only where {@code itms.events.enabled=true}. Until then rows wait in the outbox, nothing is lost.
 */
@AutoConfiguration(after = ItmsPlatformAutoConfiguration.class,
        afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@EnableConfigurationProperties(EventProperties.class)
public class ItmsEventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DomainEventRecorder.class)
    @ConditionalOnBean(JdbcClient.class)
    DomainEventRecorder itmsDomainEventRecorder(JdbcClient jdbc, ObjectMapper json, CurrentActorProvider actors,
                                                Clock clock, @Value("${spring.application.name}") String service) {
        return new JdbcDomainEventRecorder(jdbc, json, actors, clock, service);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcClient.class)
    ProcessedEvents itmsProcessedEvents(JdbcClient jdbc, Clock clock) {
        return new ProcessedEvents(jdbc, clock);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "itms.events.enabled", havingValue = "true")
    @EnableScheduling
    static class Relay {

        @Bean
        @ConditionalOnMissingBean(EventPublisher.class)
        EventPublisher itmsKafkaEventPublisher(KafkaTemplate<String, String> kafka, EventProperties properties) {
            return new KafkaEventPublisher(kafka, properties.sendTimeout());
        }

        @Bean
        OutboxRelay itmsOutboxRelay(JdbcClient jdbc, PlatformTransactionManager transactions,
                                    EventPublisher publisher, ObjectMapper json, Clock clock,
                                    ObjectProvider<MeterRegistry> meters, EventProperties properties,
                                    @Value("${spring.application.name}") String service) {
            return new OutboxRelay(jdbc, new TransactionTemplate(transactions), publisher, json, clock,
                    meters.getIfAvailable(SimpleMeterRegistry::new), properties.auditTopic(),
                    properties.domainTopicFor(service), properties.batchSize());
        }
    }
}

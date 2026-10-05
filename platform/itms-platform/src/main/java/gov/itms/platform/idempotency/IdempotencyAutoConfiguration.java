package gov.itms.platform.idempotency;

import gov.itms.platform.ItmsPlatformAutoConfiguration;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code Idempotency-Key} handling, opt-in per service with {@code itms.idempotency.enabled=true} (ADR-032). The
 * service creates the {@code idempotency_key} table in its own migrations (DATA_MODEL §6).
 */
@AutoConfiguration(after = ItmsPlatformAutoConfiguration.class)
@ConditionalOnProperty(name = "itms.idempotency.enabled", havingValue = "true")
@EnableConfigurationProperties(IdempotencyProperties.class)
@EnableScheduling
public class IdempotencyAutoConfiguration {

    /**
     * Directly after the Spring Security filter chain (order -100), so the caller is known and an unauthenticated
     * request never reaches the store.
     */
    static final int FILTER_ORDER = -99;

    @Bean
    @ConditionalOnMissingBean
    IdempotencyStore itmsIdempotencyStore(JdbcClient jdbc) {
        return new JdbcIdempotencyStore(jdbc);
    }

    @Bean
    IdempotencyKeys itmsIdempotencyKeys(IdempotencyStore store, Clock clock, IdempotencyProperties properties) {
        return new IdempotencyKeys(store, clock, properties.retention(), properties.lockTimeout());
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> itmsIdempotencyFilter(IdempotencyKeys keys,
                                                                    IdempotencyProperties properties,
                                                                    MessageSource messages, ObjectMapper json) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(
                new IdempotencyFilter(keys, properties.paths(), messages, json));
        registration.setOrder(FILTER_ORDER);
        return registration;
    }

    @Bean
    IdempotencyCleanupJob itmsIdempotencyCleanupJob(IdempotencyKeys keys, IdempotencyProperties properties) {
        return new IdempotencyCleanupJob(keys, properties.cleanupBatchSize());
    }
}

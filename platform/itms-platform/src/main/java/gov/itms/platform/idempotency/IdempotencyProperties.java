package gov.itms.platform.idempotency;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code Idempotency-Key} handling (API_SPECIFICATION §2, ADR-017, ADR-032), {@code itms.idempotency.*}. A service
 * that turns it on must create the {@code idempotency_key} table in its own database (DATA_MODEL §6).
 *
 * @param enabled          whether the filter runs in this service
 * @param paths            POST path patterns ({@code /v1/transactions/*}{@code /submissions}) that require the key
 * @param retention        how long a completed response is kept and replayed; after it the key may be used again
 * @param lockTimeout      how long a request may hold its key before a retry with the same key may take it over
 * @param cleanupInterval  pause between two runs of the clean-up of expired keys
 * @param cleanupBatchSize expired keys deleted per statement
 */
@ConfigurationProperties("itms.idempotency")
public record IdempotencyProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue List<String> paths,
        @DefaultValue("24h") Duration retention,
        @DefaultValue("30s") Duration lockTimeout,
        @DefaultValue("15m") Duration cleanupInterval,
        @DefaultValue("500") int cleanupBatchSize) {

    public IdempotencyProperties {
        paths = paths == null ? List.of() : List.copyOf(paths);
        if (enabled && paths.isEmpty()) {
            throw new IllegalArgumentException("itms.idempotency.paths must list at least one path when enabled");
        }
        if (retention == null || retention.compareTo(Duration.ofHours(1)) < 0
                || retention.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("itms.idempotency.retention must be 1 hour to 7 days (NFR-042, Q-056)");
        }
        if (lockTimeout == null || lockTimeout.isNegative() || lockTimeout.isZero()
                || lockTimeout.compareTo(retention) >= 0) {
            throw new IllegalArgumentException("itms.idempotency.lock-timeout must be positive and below retention");
        }
        if (cleanupInterval == null || cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            throw new IllegalArgumentException("itms.idempotency.cleanup-interval must be positive");
        }
        if (cleanupBatchSize < 1 || cleanupBatchSize > 10_000) {
            throw new IllegalArgumentException("itms.idempotency.cleanup-batch-size must be 1-10000");
        }
    }
}

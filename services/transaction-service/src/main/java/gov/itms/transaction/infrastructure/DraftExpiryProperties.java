package gov.itms.transaction.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Draft expiry (CC-024, ADR-031), {@code itms.transaction.draft-expiry.*}. Nothing here is hard-coded business policy:
 * every value can be changed per environment without a release (NFR-042).
 *
 * @param enabled   whether this instance runs the job (several instances may; they never expire a Draft twice)
 * @param period    how long a Draft may stay unsubmitted, counted from opening; the BRD default is 30 days
 * @param interval  pause between the end of one run and the start of the next
 * @param batchSize Drafts expired per database transaction
 * @param maxBatches batches per run at most, so one run cannot hold an instance for long; the rest wait for the next
 */
@ConfigurationProperties("itms.transaction.draft-expiry")
public record DraftExpiryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30d") Duration period,
        @DefaultValue("15m") Duration interval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("50") int maxBatches) {

    public DraftExpiryProperties {
        if (period == null || period.compareTo(Duration.ofDays(1)) < 0 || period.compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalArgumentException("itms.transaction.draft-expiry.period must be 1 to 90 days "
                    + "(CC-024, NFR-042, Q-056)");
        }
        if (interval == null || interval.isNegative() || interval.isZero()
                || interval.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("itms.transaction.draft-expiry.interval must be positive and at most "
                    + "1 day (NFR-042)");
        }
        if (batchSize < 1 || batchSize > 1000 || maxBatches < 1 || maxBatches > 1000) {
            throw new IllegalArgumentException(
                    "itms.transaction.draft-expiry.batch-size and max-batches must be 1-1000 (NFR-042)");
        }
    }
}

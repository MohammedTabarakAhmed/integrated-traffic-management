package gov.itms.platform.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes expired idempotency keys (DATA_MODEL §6), so stored responses are not kept beyond the retention. Safe on
 * every instance at once; a failed run is logged and retried on the next one.
 */
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);
    private static final int MAX_BATCHES = 100;

    private final IdempotencyKeys keys;
    private final int batchSize;

    public IdempotencyCleanupJob(IdempotencyKeys keys, int batchSize) {
        this.keys = keys;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelayString = "${itms.idempotency.cleanup-interval:15m}",
            fixedDelayString = "${itms.idempotency.cleanup-interval:15m}")
    public void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("Idempotency key clean-up failed; it is retried on the next run", e);
        }
    }

    public int run() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = keys.deleteExpired(batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("Idempotency clean-up: {} expired keys deleted", total);
        }
        return total;
    }
}

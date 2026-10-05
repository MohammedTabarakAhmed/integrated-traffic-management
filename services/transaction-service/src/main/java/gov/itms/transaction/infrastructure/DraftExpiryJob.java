package gov.itms.transaction.infrastructure;

import gov.itms.transaction.application.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Expires Drafts not submitted within the configured period (CC-024, ADR-031). Each batch is its own database
 * transaction and skips Drafts that another instance or a submission holds, so the job is safe on every instance at
 * once. A failed batch is logged and retried on the next run; it never stops the schedule.
 */
public class DraftExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(DraftExpiryJob.class);

    private final TransactionService transactions;
    private final DraftExpiryProperties properties;

    public DraftExpiryJob(TransactionService transactions, DraftExpiryProperties properties) {
        this.transactions = transactions;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${itms.transaction.draft-expiry.interval:15m}",
            fixedDelayString = "${itms.transaction.draft-expiry.interval:15m}")
    public void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            LOG.error("Draft expiry run failed; it is retried on the next run", e);
        }
    }

    /** One run: batches until fewer than a full batch is expired or {@code maxBatches} is reached. */
    public int run() {
        int total = 0;
        for (int batch = 0; batch < properties.maxBatches(); batch++) {
            int expired = transactions.expireDrafts(properties.period(), properties.batchSize());
            total += expired;
            if (expired < properties.batchSize()) {
                break;
            }
        }
        if (total > 0) {
            LOG.info("Draft expiry: {} Drafts not submitted within {} expired", total, properties.period());
        }
        return total;
    }
}

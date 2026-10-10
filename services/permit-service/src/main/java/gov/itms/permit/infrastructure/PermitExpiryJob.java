package gov.itms.permit.infrastructure;

import gov.itms.permit.application.PermitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "itms.permits.expiry-enabled", havingValue = "true", matchIfMissing = true)
class PermitExpiryJob {
    private static final Logger LOG = LoggerFactory.getLogger(PermitExpiryJob.class);
    private final PermitService permits;

    PermitExpiryJob(PermitService permits) { this.permits = permits; }

    @Scheduled(fixedDelayString = "${itms.permits.expiry-interval:PT5M}",
            initialDelayString = "${itms.permits.expiry-initial-delay:PT30S}")
    void run() {
        try {
            int expired = permits.expireDue(200);
            if (expired > 0) LOG.info("Expired {} training permits (TR-001)", expired);
        } catch (RuntimeException e) {
            LOG.warn("Training permit expiry run failed: {}", e.getClass().getSimpleName());
        }
    }
}

package gov.itms.platform.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Event relay settings (ADR-040, NFR-042). The relay runs only with {@code itms.events.enabled=true}; topics and the
 * broker address are set per environment.
 *
 * @param enabled      relay outbox rows to the broker
 * @param auditTopic   topic for audit records (consumed by audit-service and the SIEM feed)
 * @param domainTopic  topic for this service's domain events; blank means {@code itms.<service>.events.v1}
 * @param batchSize    rows per relay run and outbox, 1..1000
 * @param sendTimeout  how long to wait for the broker's acknowledgement, 1s..60s
 */
@ConfigurationProperties("itms.events")
public record EventProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("itms.audit.v1") String auditTopic,
        @DefaultValue("") String domainTopic,
        @DefaultValue("100") int batchSize,
        @DefaultValue("10s") Duration sendTimeout) {

    public EventProperties {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("itms.events.batch-size must be 1..1000, was " + batchSize);
        }
        if (sendTimeout.compareTo(Duration.ofSeconds(1)) < 0 || sendTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("itms.events.send-timeout must be 1s..60s, was " + sendTimeout);
        }
    }

    public String domainTopicFor(String service) {
        return domainTopic == null || domainTopic.isBlank() ? "itms." + service + ".events.v1" : domainTopic;
    }
}

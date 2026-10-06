package gov.itms.audit.infrastructure;

import gov.itms.audit.application.AuditService;
import gov.itms.audit.domain.AuditRecord;
import gov.itms.platform.audit.AuditRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Wires the audit trail, its consumer of {@code itms.audit.v1} and the retention job. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditServiceConfiguration.AuditProperties.class)
public class AuditServiceConfiguration {

    /**
     * {@code itms.audit-store.*} (NFR-042).
     *
     * @param retention     how long records are kept before deletion; 1 to 30 years (BRD 18.4 default: 1 year online
     *                      + 7 years archive = 8 years)
     * @param purgeInterval pause between retention runs, 1 h to 7 days
     * @param purgeBatch    records deleted per run, 1-100000
     */
    @ConfigurationProperties("itms.audit-store")
    public record AuditProperties(@DefaultValue("2922d") Duration retention, @DefaultValue("24h") Duration purgeInterval,
                                  @DefaultValue("10000") int purgeBatch) {
        public AuditProperties {
            if (retention.compareTo(Duration.ofDays(365)) < 0 || retention.compareTo(Duration.ofDays(30 * 366)) > 0) {
                throw new IllegalArgumentException("itms.audit-store.retention must be 1 to 30 years (CC-122)");
            }
            if (purgeInterval.compareTo(Duration.ofHours(1)) < 0 || purgeInterval.compareTo(Duration.ofDays(7)) > 0) {
                throw new IllegalArgumentException("itms.audit-store.purge-interval must be 1 h to 7 days");
            }
            if (purgeBatch < 1 || purgeBatch > 100_000) {
                throw new IllegalArgumentException("itms.audit-store.purge-batch must be 1-100000");
            }
        }
    }

    @Bean
    AuditService auditService(JdbcClient jdbc, ObjectMapper json, AuditRecorder audit, Clock clock) {
        return new AuditService(new JdbcAuditStore(jdbc, json), audit, clock);
    }

    /** Appends every service's audit records (CloudEvents {@code itms.audit.recorded}) to the trail. */
    static class AuditEventListener {

        private static final Logger LOG = LoggerFactory.getLogger(AuditEventListener.class);
        private final AuditService audit;
        private final ObjectMapper json;

        AuditEventListener(AuditService audit, ObjectMapper json) {
            this.audit = audit;
            this.json = json;
        }

        @KafkaListener(topics = "${itms.events.audit-topic:itms.audit.v1}",
                groupId = "${itms.audit-store.group-id:audit-service}")
        void onMessage(String message) {
            JsonNode d;
            try {
                d = json.readTree(message).get("data");
            } catch (RuntimeException e) {
                LOG.warn("Skipping an audit event that is not JSON");
                return;
            }
            if (d == null || !d.hasNonNull("auditId") || !d.hasNonNull("occurredAt") || !d.hasNonNull("service")) {
                LOG.warn("Skipping an audit event without auditId, occurredAt or service");
                return;
            }
            List<String> roles = new ArrayList<>();
            if (d.has("actorRoles")) {
                d.get("actorRoles").forEach(r -> roles.add(r.asString()));
            }
            AuditRecord record;
            try {
                record = new AuditRecord(0, UUID.fromString(d.get("auditId").asString()),
                    Instant.parse(d.get("occurredAt").asString()), d.get("service").asString(), text(d, "action"),
                    text(d, "entityType"), text(d, "entityId"), text(d, "actorId"), text(d, "actorUsername"), roles,
                    text(d, "channel"), text(d, "ipAddress"), text(d, "correlationId"), raw(d, "oldValue"),
                    raw(d, "newValue"), text(d, "reason"), null, null, null);
            } catch (RuntimeException e) {
                LOG.warn("Skipping a malformed audit event: {}", e.getClass().getSimpleName());
                return;
            }
            audit.ingest(record);
        }

        private static String text(JsonNode d, String field) {
            return d.hasNonNull(field) ? d.get(field).asString() : null;
        }

        private String raw(JsonNode d, String field) {
            return d.hasNonNull(field) ? json.writeValueAsString(d.get(field)) : null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "itms.audit-store.consumer.enabled", havingValue = "true")
    static class ConsumerConfiguration {

        @Bean
        AuditEventListener auditEventListener(AuditService audit, ObjectMapper json) {
            return new AuditEventListener(audit, json);
        }
    }

    /** Deletes records past retention (CC-122). */
    public static class RetentionJob {

        private static final Logger LOG = LoggerFactory.getLogger(RetentionJob.class);
        private final AuditService audit;
        private final AuditProperties properties;

        public RetentionJob(AuditService audit, AuditProperties properties) {
            this.audit = audit;
            this.properties = properties;
        }

        @Scheduled(initialDelayString = "${itms.audit-store.purge-interval:24h}",
                fixedDelayString = "${itms.audit-store.purge-interval:24h}")
        public void run() {
            try {
                int n = audit.purge(properties.retention(), properties.purgeBatch());
                if (n > 0) {
                    LOG.info("Audit retention: {} records past {} deleted", n, properties.retention());
                }
            } catch (RuntimeException e) {
                LOG.error("Audit retention run failed; it is retried on the next run", e);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "itms.audit-store.retention-enabled", havingValue = "true", matchIfMissing = true)
    static class RetentionConfiguration {

        @Bean
        RetentionJob retentionJob(AuditService audit, AuditProperties properties) {
            return new RetentionJob(audit, properties);
        }
    }
}

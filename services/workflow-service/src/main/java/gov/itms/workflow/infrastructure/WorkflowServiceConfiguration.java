package gov.itms.workflow.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.workflow.application.WorkflowService;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;

/** Wires the application layer to its JDBC adapter, and the SLA escalation job (CC-062). */
@Configuration(proxyBeanMethods = false)
public class WorkflowServiceConfiguration {

    @Bean
    WorkflowService workflowService(JdbcClient jdbc, ObjectMapper json, AuditRecorder audit,
                                    DomainEventRecorder events, Clock clock) {
        return new WorkflowService(new JdbcWorkflowRepository(jdbc, json), audit, events, clock);
    }

    /**
     * SLA escalation (CC-062), {@code itms.workflow.escalation.*}; safe on every instance at once (rows are locked and
     * skipped by the others).
     *
     * @param enabled   whether this instance runs the job
     * @param interval  pause between runs, 10 seconds to 1 hour
     * @param batchSize items escalated per database transaction, 1-1000
     */
    @ConfigurationProperties("itms.workflow.escalation")
    public record EscalationProperties(@DefaultValue("true") boolean enabled, @DefaultValue("1m") Duration interval,
                                       @DefaultValue("100") int batchSize) {
        public EscalationProperties {
            if (interval == null || interval.compareTo(Duration.ofSeconds(10)) < 0
                    || interval.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("itms.workflow.escalation.interval must be 10 s to 1 h (NFR-042)");
            }
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException("itms.workflow.escalation.batch-size must be 1-1000 (NFR-042)");
            }
        }
    }

    /** Runs the escalation in batches; a failed run is logged and retried on the next one. */
    public static class EscalationJob {

        private static final Logger LOG = LoggerFactory.getLogger(EscalationJob.class);
        private final WorkflowService workflow;
        private final EscalationProperties properties;

        public EscalationJob(WorkflowService workflow, EscalationProperties properties) {
            this.workflow = workflow;
            this.properties = properties;
        }

        @Scheduled(initialDelayString = "${itms.workflow.escalation.interval:1m}",
                fixedDelayString = "${itms.workflow.escalation.interval:1m}")
        public void scheduled() {
            try {
                int total = 0;
                for (int batch = 0; batch < 50; batch++) {
                    int n = workflow.escalateOverdue(properties.batchSize());
                    total += n;
                    if (n < properties.batchSize()) {
                        break;
                    }
                }
                if (total > 0) {
                    LOG.info("SLA escalation: {} overdue work items escalated", total);
                }
            } catch (RuntimeException e) {
                LOG.error("SLA escalation run failed; it is retried on the next run", e);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(EscalationProperties.class)
    @ConditionalOnProperty(name = "itms.workflow.escalation.enabled", havingValue = "true", matchIfMissing = true)
    static class EscalationConfiguration {

        @Bean
        EscalationJob escalationJob(WorkflowService workflow, EscalationProperties properties) {
            return new EscalationJob(workflow, properties);
        }
    }
}

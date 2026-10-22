package gov.itms.reporting.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.reporting.application.KpiSettingsService;
import gov.itms.reporting.application.Pseudonymiser;
import gov.itms.reporting.application.ReportingService;
import gov.itms.reporting.domain.Report;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Wires the warehouse, the pseudonymiser, the report definitions and the event intake. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReportingProperties.class)
public class ReportingConfiguration {

    @Bean
    ReportingService reportingService(JdbcClient jdbc, ObjectMapper json, ReportingProperties properties,
                                      AuditRecorder audit, Clock clock) {
        return new ReportingService(new JdbcWarehouse(jdbc, json),
                new Pseudonymiser(properties.pseudonymKey().getBytes(StandardCharsets.UTF_8),
                        Set.copyOf(properties.personalFields())),
                audit, new ReportingService.Settings(properties.reports().stream()
                        .map(r -> new Report.Definition(r.code(), r.titleNl(), r.titleEn(), r.eventTypes(),
                                r.amountField())).toList(), properties.maxDays()), clock);
    }

    /** KPI targets and thresholds (RPT-012), wired beside the warehouse in the same database. */
    @Bean
    KpiSettingsService kpiSettingsService(JdbcClient jdbc, AuditRecorder audit, DomainEventRecorder events,
                                          Clock clock) {
        return new KpiSettingsService(new JdbcKpiSettings(jdbc), audit, events, clock);
    }

    /** The intake of domain events (RPT-020): off unless the broker is available, like the outbox relay. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "itms.reporting.consumer.enabled", havingValue = "true")
    static class IntakeConfiguration {

        @Bean
        EventIntake eventIntake(ReportingService reporting, ObjectMapper json) {
            return new EventIntake(reporting, json);
        }
    }

    /**
     * Consumes the modules' domain events (CloudEvents JSON, ADR-002). A malformed event is logged and skipped, so one
     * bad message cannot block a topic; redeliveries are ignored by the warehouse key.
     */
    static class EventIntake {

        private static final Logger LOG = LoggerFactory.getLogger(EventIntake.class);
        private final ReportingService reporting;
        private final ObjectMapper json;

        EventIntake(ReportingService reporting, ObjectMapper json) {
            this.reporting = reporting;
            this.json = json;
        }

        @KafkaListener(topics = "#{'${itms.reporting.consumer.topics}'.split(',')}",
                groupId = "${itms.reporting.consumer.group-id:reporting-service}")
        void onMessage(String message) {
            JsonNode event;
            try {
                event = json.readTree(message);
            } catch (RuntimeException e) {
                LOG.warn("Skipping an event that is not JSON");
                return;
            }
            if (!event.hasNonNull("id") || !event.hasNonNull("type")) {
                LOG.warn("Skipping an event without id or type");
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            JsonNode d = event.get("data");
            if (d != null && d.isObject()) {
                d.properties().forEach(e -> {
                    JsonNode v = e.getValue();
                    if (v.isValueNode() && !v.isNull()) {
                        data.put(e.getKey(), v.asString());
                    }
                });
            }
            Instant time = null;
            if (event.hasNonNull("time")) {
                try {
                    time = Instant.parse(event.get("time").asString());
                } catch (RuntimeException e) {
                    LOG.warn("Event {} has an unreadable time; the intake time is used", event.get("id").asString());
                }
            }
            reporting.ingest(event.get("id").asString(), event.get("type").asString(),
                    event.hasNonNull("subject") ? event.get("subject").asString() : null, time, data);
        }
    }
}

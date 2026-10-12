package gov.itms.examination.infrastructure;

import gov.itms.examination.application.ExaminationService;
import gov.itms.examination.application.Ports;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.client.ServiceCallException;
import gov.itms.platform.client.ServiceClientProperties;
import gov.itms.platform.client.ServiceClients;
import gov.itms.platform.events.DomainEventRecorder;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

/** Wires examination-service (ADR-065). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ExaminationConfiguration.ExaminationProperties.class)
public class ExaminationConfiguration {

    /**
     * {@code itms.examination.*} (DL-031, DL-032, DL-037, DL-043): values are provisional until the Authority
     * confirms them (ADR-064 decision 13).
     *
     * @param classTests licence class -> comma-separated test type codes to pass
     */
    @ConfigurationProperties("itms.examination")
    public record ExaminationProperties(@DefaultValue("12") int fileExpiryMonths,
                                        @DefaultValue("3") int failedAttemptsThreshold,
                                        @DefaultValue("10") int extraTrainingHours,
                                        Map<String, String> classTests,
                                        @DefaultValue("Asia/Dubai") String zone,
                                        @DefaultValue("07:00") String workingFrom,
                                        @DefaultValue("20:00") String workingTo,
                                        @DefaultValue("10m") Duration minimumRoute,
                                        @DefaultValue("200") int batchSize) {

        public ExaminationProperties {
            classTests = classTests == null ? Map.of() : Map.copyOf(classTests);
        }

        ExaminationService.Rules rules() {
            Map<String, List<String>> tests = new LinkedHashMap<>();
            classTests.forEach((k, v) ->
                    tests.put(k.toUpperCase(java.util.Locale.ROOT), List.of(v.split("\\s*,\\s*"))));
            return new ExaminationService.Rules(fileExpiryMonths, failedAttemptsThreshold, extraTrainingHours, tests,
                    ZoneId.of(zone), LocalTime.parse(workingFrom), LocalTime.parse(workingTo), minimumRoute);
        }
    }

    @Bean
    ExaminationService examinationService(JdbcClient jdbc, ObjectMapper json, ExaminationProperties properties,
                                          Environment environment, AuditRecorder audit, DomainEventRecorder events,
                                          Clock clock, ObjectProvider<ObservationRegistry> observations) {
        ExaminationService.Rules rules = properties.rules();
        ServiceClientProperties client = Binder.get(environment)
                .bind("itms.clients.licensing", ServiceClientProperties.class)
                .orElseThrow(() -> new IllegalStateException("itms.clients.licensing is not configured"))
                .validate("itms.clients.licensing");
        RestClient licensing = ServiceClients.create(client, clock,
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
        return new ExaminationService(new JdbcExamRepository(jdbc, json, rules.zone()), new RemoteLicensing(licensing),
                audit, events, clock, rules);
    }

    @Bean
    Jobs examinationJobs(ExaminationService service, ExaminationProperties properties) {
        return new Jobs(service, properties.batchSize());
    }

    /** licensing-service {@code POST /v1/examination-transfers} (DL-036), idempotent per learning file. */
    static class RemoteLicensing implements Ports.Licensing {

        private final RestClient client;

        RemoteLicensing(RestClient client) {
            this.client = client;
        }

        record Body(String examinationFileRef, String trafficFileNumber, String classCode, String gearbox,
                    LocalDate passedOn, String region) {
        }

        record Answer(String id) {
        }

        @Override
        public String transfer(String ref, String trafficFileNumber, String classCode, String gearbox,
                               LocalDate passedOn, String region) {
            try {
                Answer a = client.post().uri("/v1/examination-transfers").header("X-Channel", "BACKOFFICE")
                        .header("Idempotency-Key", "exam-transfer-" + ref).contentType(MediaType.APPLICATION_JSON)
                        .body(new Body(ref, trafficFileNumber, classCode, gearbox, passedOn, region))
                        .retrieve().body(Answer.class);
                if (a == null || a.id() == null) {
                    throw new IllegalStateException("licensing answered without a transfer id");
                }
                return a.id();
            } catch (RestClientException | ServiceCallException e) {
                throw new IllegalStateException("licensing-service did not take the transfer", e);
            }
        }
    }

    /** Learning file expiry (DL-031) and the licensing hand-off retry (DL-036). */
    public static class Jobs {

        private static final Logger LOG = LoggerFactory.getLogger(Jobs.class);
        private final ExaminationService service;
        private final int batchSize;

        Jobs(ExaminationService service, int batchSize) {
            this.service = service;
            this.batchSize = batchSize;
        }

        @Scheduled(fixedDelayString = "${itms.examination.expiry-interval:PT1H}",
                initialDelayString = "${itms.examination.expiry-initial-delay:PT1M}")
        public void expire() {
            int n = service.expireStale(batchSize);
            if (n > 0) {
                LOG.info("Expired {} learning files without progress", n);
            }
        }

        @Scheduled(fixedDelayString = "${itms.examination.transfer-interval:PT30S}",
                initialDelayString = "${itms.examination.transfer-initial-delay:PT20S}")
        public void transfer() {
            int n = service.sendTransfers(batchSize);
            if (n > 0) {
                LOG.info("Handed {} passed learning files to licensing", n);
            }
        }
    }
}

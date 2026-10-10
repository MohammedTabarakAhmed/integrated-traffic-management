package gov.itms.licensing.infrastructure;

import gov.itms.licensing.application.ForeignService;
import gov.itms.licensing.application.LicenceClassService;
import gov.itms.licensing.application.LicenceNumberService;
import gov.itms.licensing.application.LicenceService;
import gov.itms.licensing.application.LicenceStatusService;
import gov.itms.licensing.application.LicenceTransactions;
import gov.itms.licensing.application.LicensingRules;
import gov.itms.licensing.domain.LicenceRequest;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.client.ServiceClientProperties;
import gov.itms.platform.client.ServiceClients;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.events.ProcessedEvents;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires licensing to PostgreSQL, fees-service, payment-service, hold-eligibility-service, the payment and traffic file
 * events, and the automatic renewal job.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LicensingServiceConfiguration.LicensingProperties.class,
        LicensingServiceConfiguration.StatusProperties.class, LicensingServiceConfiguration.ForeignProperties.class})
public class LicensingServiceConfiguration {

    /**
     * {@code itms.licensing.*} (NFR-042). Code-keyed maps are written with brackets ({@code "[ISSUE]": LIC_ISSUE}).
     *
     * @param probationMonths     BRD default 2 years (DL-050)
     * @param serviceCodes        fees-service and hold-eligibility-service code per paid transaction
     * @param conditionCodes      the licence conditions that may be recorded (DL-023)
     * @param renewalWindowDays   BRD default 90 days (DL-051)
     * @param eyeTestValidityDays how old a passed eye test may be for renewal (Q-077)
     * @param lensesCondition     condition recorded when the eye test needs corrective lenses
     * @param autoRenewal         the automatic renewal job (DL-065)
     */
    @ConfigurationProperties("itms.licensing")
    public record LicensingProperties(@DefaultValue("24") int probationMonths, Map<String, String> serviceCodes,
                                      List<String> conditionCodes, @DefaultValue("90") int renewalWindowDays,
                                      @DefaultValue("180") int eyeTestValidityDays, String lensesCondition,
                                      @DefaultValue AutoRenewal autoRenewal) {

        /** DL-065: notice period, run interval and batch size of the job. */
        public record AutoRenewal(@DefaultValue("true") boolean enabled, @DefaultValue("14") int noticeDays,
                                  @DefaultValue("1h") Duration interval, @DefaultValue("200") int batchSize) {
        }

        public LicensingProperties {
            serviceCodes = serviceCodes == null ? Map.of() : Map.copyOf(serviceCodes);
            conditionCodes = conditionCodes == null ? List.of() : List.copyOf(conditionCodes);
            for (String c : conditionCodes) {
                if (c == null || !c.matches("^[A-Z][A-Z0-9_]{0,39}$")) {
                    throw new IllegalArgumentException("itms.licensing.condition-codes has an invalid entry (DL-023)");
                }
            }
            if (autoRenewal == null || autoRenewal.batchSize() < 1 || autoRenewal.batchSize() > 5000) {
                throw new IllegalArgumentException("itms.licensing.auto-renewal.batch-size must be 1-5000");
            }
        }

        LicensingRules rules() {
            Map<LicenceRequest.Kind, String> byKind = new EnumMap<>(LicenceRequest.Kind.class);
            serviceCodes.forEach((k, v) -> byKind.put(LicenceRequest.Kind.valueOf(k), v));
            return new LicensingRules(probationMonths, byKind, Set.copyOf(conditionCodes), renewalWindowDays,
                    eyeTestValidityDays, autoRenewal.noticeDays(),
                    lensesCondition == null || lensesCondition.isBlank() ? null : lensesCondition);
        }
    }

    /**
     * {@code itms.licensing.status.*}: cancellation after the Nth black-point suspension (0 = never, CONF-001), the
     * no-objection certificate validity (Q-078) and the suspension end job.
     */
    @ConfigurationProperties("itms.licensing.status")
    public record StatusProperties(@DefaultValue("0") int cancelAfterSuspensions,
                                   @DefaultValue("30") int nocValidityDays,
                                   @DefaultValue("15m") Duration suspensionEndInterval) {

        LicenceStatusService.StatusRules rules() {
            return new LicenceStatusService.StatusRules(cancelAfterSuspensions, nocValidityDays);
        }
    }

    /**
     * {@code itms.licensing.foreign.*}: accepted languages (DL-054), fixed permit validities (DL-055), international
     * permit validity and approved printers (DL-056); interim answers in Q-079.
     */
    @ConfigurationProperties("itms.licensing.foreign")
    public record ForeignProperties(List<String> acceptedLanguages, @DefaultValue("30") int visitorPermitDays,
                                    @DefaultValue("30") int cardPendingPermitDays,
                                    @DefaultValue("12") int idpValidityMonths, List<String> idpPrinters,
                                    @DefaultValue("false") boolean exchangeProbation) {

        ForeignService.ForeignRules rules() {
            return new ForeignService.ForeignRules(acceptedLanguages == null ? Set.of() : Set.copyOf(acceptedLanguages),
                    visitorPermitDays, cardPendingPermitDays, idpValidityMonths,
                    idpPrinters == null ? Set.of() : Set.copyOf(idpPrinters), exchangeProbation);
        }
    }

    @Bean
    JdbcLicenceClassRepository licenceClassRepository(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcLicenceClassRepository(jdbc, json);
    }

    @Bean
    LicenceClassService licenceClassService(JdbcLicenceClassRepository classes, AuditRecorder audit,
                                            DomainEventRecorder events, Clock clock) {
        return new LicenceClassService(classes, audit, events, clock);
    }

    @Bean
    JdbcLicenceNumberRepository licenceNumberRepository(JdbcClient jdbc) {
        return new JdbcLicenceNumberRepository(jdbc);
    }

    @Bean
    LicenceNumberService licenceNumberService(JdbcLicenceNumberRepository ranges, AuditRecorder audit, Clock clock) {
        return new LicenceNumberService(ranges, audit, clock);
    }

    @Bean
    JdbcLicenceRepository licenceRepository(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcLicenceRepository(jdbc, json);
    }

    @Bean
    LicenceService licenceService(JdbcLicenceRepository repo, LicenceClassService classes,
                                  LicenceNumberService numbers, LicensingProperties properties,
                                  Environment environment, AuditRecorder audit, DomainEventRecorder events,
                                  Clock clock, ObjectProvider<ObservationRegistry> observations) {
        ObservationRegistry registry = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        return new LicenceService(repo, classes, numbers,
                new Adapters.RemoteFeeQuotes(ServiceClients.create(client(environment, "fees"), clock, registry)),
                new Adapters.RemotePayables(ServiceClients.create(client(environment, "payments"), clock, registry)),
                new Adapters.RemoteEligibility(ServiceClients.create(client(environment, "holds"), clock, registry)),
                properties.rules(), audit, events, clock);
    }

    @Bean
    LicenceTransactions licenceTransactions(LicenceService licences, AuditRecorder audit, DomainEventRecorder events,
                                            ProcessedEvents processed, Clock clock) {
        LicenceTransactions transactions = new LicenceTransactions(licences, audit, events, processed, clock);
        licences.addHandler(transactions);
        return transactions;
    }

    @Bean
    LicenceStatusService licenceStatusService(LicenceService licences, StatusProperties properties,
                                              AuditRecorder audit, DomainEventRecorder events,
                                              ProcessedEvents processed, Clock clock) {
        LicenceStatusService status = new LicenceStatusService(licences, properties.rules(), audit, events,
                processed, clock);
        licences.addHandler(status);
        return status;
    }

    @Bean
    JdbcForeignRepository foreignRepository(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcForeignRepository(jdbc, json);
    }

    @Bean
    ForeignService foreignService(LicenceService licences, JdbcForeignRepository foreign,
                                  LicenceNumberService numbers, ForeignProperties properties, AuditRecorder audit,
                                  DomainEventRecorder events, Clock clock) {
        ForeignService service = new ForeignService(licences, foreign, numbers, properties.rules(), audit, events,
                clock);
        licences.addHandler(service);
        return service;
    }

    private static ServiceClientProperties client(Environment environment, String name) {
        return Binder.get(environment).bind("itms.clients." + name, ServiceClientProperties.class)
                .orElseThrow(() -> new IllegalStateException("itms.clients." + name + " is not configured"))
                .validate("itms.clients." + name);
    }

    /** Sends automatic renewal notices and runs due renewals (DL-065); each licence in its own transaction. */
    public static class AutoRenewalJob {

        private static final Logger LOG = LoggerFactory.getLogger(AutoRenewalJob.class);
        private final LicenceTransactions transactions;
        private final int batchSize;

        public AutoRenewalJob(LicenceTransactions transactions, int batchSize) {
            this.transactions = transactions;
            this.batchSize = batchSize;
        }

        @Scheduled(initialDelayString = "${itms.licensing.auto-renewal.interval:1h}",
                fixedDelayString = "${itms.licensing.auto-renewal.interval:1h}")
        public void run() {
            int done = 0;
            for (UUID licenceId : transactions.autoRenewalsDue(batchSize)) {
                try {
                    if (!"NOTHING".equals(transactions.autoRenewOne(licenceId))) {
                        done++;
                    }
                } catch (RuntimeException e) {
                    LOG.error("Automatic renewal of licence {} failed; retried on the next run", licenceId, e);
                }
            }
            if (done > 0) {
                LOG.info("Automatic renewal: {} licences notified or renewed", done);
            }
        }
    }

    /** Puts licences back in force the day after their suspension ends (DL-058, UAT-1). */
    public static class SuspensionEndJob {

        private static final Logger LOG = LoggerFactory.getLogger(SuspensionEndJob.class);
        private final LicenceStatusService status;

        public SuspensionEndJob(LicenceStatusService status) {
            this.status = status;
        }

        @Scheduled(initialDelayString = "${itms.licensing.status.suspension-end-interval:15m}",
                fixedDelayString = "${itms.licensing.status.suspension-end-interval:15m}")
        public void run() {
            int n = 0;
            for (UUID licenceId : status.suspensionsEnded(500)) {
                try {
                    if (status.endSuspension(licenceId)) {
                        n++;
                    }
                } catch (RuntimeException e) {
                    LOG.error("Ending the suspension of licence {} failed; retried on the next run", licenceId, e);
                }
            }
            if (n > 0) {
                LOG.info("Suspensions ended: {} licences in force again", n);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SuspensionEndConfiguration {

        @Bean
        SuspensionEndJob suspensionEndJob(LicenceStatusService status) {
            return new SuspensionEndJob(status);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "itms.licensing.auto-renewal.enabled", havingValue = "true", matchIfMissing = true)
    static class AutoRenewalConfiguration {

        @Bean
        AutoRenewalJob autoRenewalJob(LicenceTransactions transactions, LicensingProperties properties) {
            return new AutoRenewalJob(transactions, properties.autoRenewal().batchSize());
        }
    }

    /** Completes paid requests from payment events and follows traffic file changes (off without a broker). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "itms.licensing.consumer-enabled", havingValue = "true")
    static class ConsumerConfiguration {

        @Bean
        Adapters.PaymentEventListener paymentEventListener(LicenceService licences, ObjectMapper json) {
            return new Adapters.PaymentEventListener(licences, json);
        }

        @Bean
        Adapters.TrafficFileEventListener trafficFileEventListener(LicenceTransactions transactions,
                                                                   LicenceStatusService status, ObjectMapper json) {
            return new Adapters.TrafficFileEventListener(transactions, status, json);
        }

        @Bean
        Adapters.ViolationEventListener violationEventListener(LicenceStatusService status, ObjectMapper json) {
            return new Adapters.ViolationEventListener(status, json);
        }
    }
}

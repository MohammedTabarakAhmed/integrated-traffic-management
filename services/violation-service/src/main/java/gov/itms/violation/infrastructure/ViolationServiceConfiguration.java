package gov.itms.violation.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.client.OwnTrafficFile;
import gov.itms.platform.client.ServiceClientProperties;
import gov.itms.platform.client.ServiceClients;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.violation.application.ViolationService;
import gov.itms.violation.application.CrossborderViolations;
import gov.itms.violation.application.CustomerViolations;
import gov.itms.violation.application.InstalmentService;
import gov.itms.violation.application.ObjectionService;
import gov.itms.violation.application.ViolationBooks;
import gov.itms.violation.domain.BlackPoints;
import gov.itms.violation.domain.InstalmentPlan;
import gov.itms.violation.domain.UnpaidHold;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
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

/** Wires violations to PostgreSQL, payment-service, hold-eligibility-service and the payment events. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ViolationServiceConfiguration.ViolationProperties.class,
        ViolationServiceConfiguration.CrossborderProperties.class,
        ViolationServiceConfiguration.InstalmentProperties.class})
public class ViolationServiceConfiguration {

    /**
     * {@code itms.violations.*} (NFR-042): black point rules (VF-031..VF-033; BRD defaults 12 months, 24 points,
     * 3/6/12 months, warnings at 12 and 18), cancellation reasons (VF-021), currency, business zone, how old a reported
     * violation may be, what a suspension hold blocks, and when an unpaid violation places a hold (VF-023; interim
     * 60 days after recording, blocking vehicle renewal, Q-074).
     */
    @ConfigurationProperties("itms.violations")
    public record ViolationProperties(@DefaultValue("12") int pointsExpiryMonths,
                                      @DefaultValue("24") int suspensionThreshold,
                                      @DefaultValue({"3", "6", "12"}) List<Integer> suspensionMonths,
                                      @DefaultValue({"12", "18"}) List<Integer> warningThresholds,
                                      List<String> cancelReasons,
                                      @DefaultValue("XTS") String currency,
                                      @DefaultValue("UTC") String zone,
                                      @DefaultValue("30d") Duration maxReportDelay,
                                      List<String> suspensionBlockedServices,
                                      List<String> suspensionLiftableByRoles,
                                      @DefaultValue("true") boolean unpaidHoldEnabled,
                                      @DefaultValue("60d") Duration unpaidHoldAfter,
                                      @DefaultValue("1h") Duration unpaidHoldInterval,
                                      @DefaultValue("100") int unpaidHoldBatchSize,
                                      List<String> unpaidHoldBlockedServices,
                                      List<String> unpaidHoldLiftableByRoles,
                                      @DefaultValue("30d") Duration objectionWindow) {
        public ViolationProperties {
            suspensionMonths = List.copyOf(suspensionMonths);
            warningThresholds = List.copyOf(warningThresholds);
            cancelReasons = cancelReasons == null || cancelReasons.isEmpty()
                    ? List.of("WRONG_PLATE", "DEVICE_FAULT", "DUPLICATE", "OBJECTION_ACCEPTED", "COURT_ORDER")
                    : List.copyOf(cancelReasons);
            suspensionBlockedServices = suspensionBlockedServices == null || suspensionBlockedServices.isEmpty()
                    ? List.of("*") : List.copyOf(suspensionBlockedServices);
            suspensionLiftableByRoles = suspensionLiftableByRoles == null || suspensionLiftableByRoles.isEmpty()
                    ? List.of("HOLD_SUPERVISOR") : List.copyOf(suspensionLiftableByRoles);
            unpaidHoldBlockedServices = unpaidHoldBlockedServices == null || unpaidHoldBlockedServices.isEmpty()
                    ? List.of("VEH_RENEWAL") : List.copyOf(unpaidHoldBlockedServices);
            unpaidHoldLiftableByRoles = unpaidHoldLiftableByRoles == null || unpaidHoldLiftableByRoles.isEmpty()
                    ? List.of("UNPAID_FINE_HOLD_MANAGER", "HOLD_SUPERVISOR") : List.copyOf(unpaidHoldLiftableByRoles);
            if (unpaidHoldInterval.compareTo(Duration.ofMinutes(1)) < 0
                    || unpaidHoldInterval.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("itms.violations.unpaid-hold-interval must be 1 minute to 1 day");
            }
            unpaidPolicy(unpaidHoldEnabled, unpaidHoldAfter, unpaidHoldBatchSize);
            if (!currency.matches("^[A-Z]{3}$")) {
                throw new IllegalArgumentException("itms.violations.currency must be an ISO 4217 code");
            }
            ZoneId.of(zone);
            if (maxReportDelay.compareTo(Duration.ofHours(1)) < 0 || maxReportDelay.compareTo(Duration.ofDays(365)) > 0) {
                throw new IllegalArgumentException("itms.violations.max-report-delay must be 1 h to 365 days");
            }
            new BlackPoints.Policy(pointsExpiryMonths, suspensionThreshold, suspensionMonths, warningThresholds);
        }
    }

    /** The signed-in customer's own violations (ADR-067 d); the file is looked up with the customer's token. */
    @Bean
    CustomerViolations customerViolations(JdbcClient jdbc, ObjectMapper json, ViolationService violations,
                                          AuditRecorder audit, Environment environment,
                                          ObjectProvider<ObservationRegistry> observations) {
        String baseUrl = environment.getRequiredProperty("itms.clients.traffic-files.base-url");
        Duration timeout = environment.getProperty("itms.clients.traffic-files.timeout", Duration.class,
                Duration.ofSeconds(3));
        return new CustomerViolations(new JdbcViolationRepository(jdbc, json),
                new Adapters.RemoteOwnFile(OwnTrafficFile.create(baseUrl, timeout,
                        observations.getIfAvailable(() -> ObservationRegistry.NOOP))),
                violations, audit);
    }

    @Bean
    ViolationBooks violationBooks(JdbcClient jdbc,ObjectMapper json,AuditRecorder audit,DomainEventRecorder events,Clock clock) {
        return new ViolationBooks(new JdbcViolationRepository(jdbc,json),audit,events,clock);
    }

    @Bean
    ViolationService violationService(JdbcClient jdbc, ObjectMapper json, ViolationProperties p,
                                      Environment environment, AuditRecorder audit, DomainEventRecorder events,
                                      Clock clock, ObjectProvider<ObservationRegistry> observations) {
        ObservationRegistry registry = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        return new ViolationService(new JdbcViolationRepository(jdbc, json),
                new Adapters.RemotePayables(ServiceClients.create(client(environment, "payments"), clock, registry)),
                new Adapters.RemoteHolds(ServiceClients.create(client(environment, "holds"), clock, registry),
                        p.suspensionBlockedServices(), p.suspensionLiftableByRoles()),
                new Adapters.RemoteUnpaidFineHolds(ServiceClients.create(client(environment, "holds"), clock,
                        registry), p.unpaidHoldBlockedServices(), p.unpaidHoldLiftableByRoles()),
                rentals(environment, clock, registry),
                audit, events,
                new ViolationService.Settings(new BlackPoints.Policy(p.pointsExpiryMonths(), p.suspensionThreshold(),
                        p.suspensionMonths(), p.warningThresholds()), Set.copyOf(p.cancelReasons()), p.currency(),
                        ZoneId.of(p.zone()), p.maxReportDelay(),
                        unpaidPolicy(p.unpaidHoldEnabled(), p.unpaidHoldAfter(), p.unpaidHoldBatchSize()),
                        pointsAt(environment), p.objectionWindow()), clock);
    }

    /**
     * Cross-border violations (Increment 9): the cancellation reason applied when a partner state accepts an objection
     * (one of the cancel reasons) and the beneficiary of payables collected for partner states (XB-004 settlement).
     */
    @ConfigurationProperties("itms.violations.crossborder")
    public record CrossborderProperties(@DefaultValue("OBJECTION_ACCEPTED") String acceptedObjectionReason,
                                        @DefaultValue("CROSSBORDER_SETTLEMENT") String beneficiary) {
    }

    @Bean
    CrossborderViolations crossborderViolations(JdbcClient jdbc, ObjectMapper json, ViolationService violations,
                                                ViolationProperties p, CrossborderProperties x,
                                                Environment environment, AuditRecorder audit,
                                                DomainEventRecorder events, Clock clock,
                                                ObjectProvider<ObservationRegistry> observations) {
        if (!p.cancelReasons().contains(x.acceptedObjectionReason())) {
            throw new IllegalArgumentException(
                    "itms.violations.crossborder.accepted-objection-reason must be one of itms.violations.cancel-reasons");
        }
        ObservationRegistry registry = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        return new CrossborderViolations(violations, new JdbcViolationRepository(jdbc, json),
                new JdbcCrossborderLinks(jdbc),
                new Adapters.RemoteVehicles(ServiceClients.create(client(environment, "vehicles"), clock, registry)),
                new Adapters.RemotePayables(ServiceClients.create(client(environment, "payments"), clock, registry)),
                audit, events, new CrossborderViolations.Settings(x.acceptedObjectionReason(), x.beneficiary()),
                clock);
    }

    /**
     * {@code itms.violations.instalments.*} (VF-071, VF-073; ADR-062, Q-083): the minimum total of a plan in minor
     * units (BRD default 5,000 local currency, so 500000 for a two-decimal currency), the allowed number of monthly
     * instalments (BRD 3 to 12) and the grace period after a due date before the holds return. These move to a
     * rules-service decision table when the business publishes one (VF-071 "set by the rules engine", BQ-4).
     */
    @ConfigurationProperties("itms.violations.instalments")
    public record InstalmentProperties(@DefaultValue("500000") long minimumTotalMinor,
                                       @DefaultValue("3") int minMonths,
                                       @DefaultValue("12") int maxMonths,
                                       @DefaultValue("15") int graceDays,
                                       @DefaultValue("1h") Duration lapseInterval) {
        public InstalmentProperties {
            new InstalmentPlan.Terms(minimumTotalMinor, minMonths, maxMonths, graceDays);
            if (lapseInterval.compareTo(Duration.ofMinutes(1)) < 0 || lapseInterval.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("itms.violations.instalments.lapse-interval must be 1 minute to 1 day");
            }
        }
    }

    @Bean
    InstalmentService instalmentService(JdbcClient jdbc, ObjectMapper json, ViolationService violations,
                                        ViolationProperties p, InstalmentProperties i, Environment environment,
                                        AuditRecorder audit, DomainEventRecorder events, Clock clock,
                                        ObjectProvider<ObservationRegistry> observations) {
        ObservationRegistry registry = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        return new InstalmentService(new JdbcInstalmentPlans(jdbc), new JdbcViolationRepository(jdbc, json),
                new JdbcCrossborderLinks(jdbc),
                new Adapters.RemotePayables(ServiceClients.create(client(environment, "payments"), clock, registry)),
                violations, audit, events,
                new InstalmentService.Settings(new InstalmentPlan.Terms(i.minimumTotalMinor(), i.minMonths(),
                        i.maxMonths(), i.graceDays()), p.currency(), ZoneId.of(p.zone())), clock);
    }

    /** Marks instalment plans LATE when an instalment lapsed (VF-073); safe on every instance (rows are locked). */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class InstalmentJobConfiguration {

        @Bean
        InstalmentLapseJob instalmentLapseJob(InstalmentService instalments) {
            return new InstalmentLapseJob(instalments);
        }
    }

    /** Plans with an instalment unpaid past its grace period become LATE; a failure is retried on the next run. */
    public static class InstalmentLapseJob {

        private static final Logger LOG = LoggerFactory.getLogger(InstalmentLapseJob.class);
        private final InstalmentService instalments;

        public InstalmentLapseJob(InstalmentService instalments) {
            this.instalments = instalments;
        }

        @Scheduled(fixedDelayString = "${itms.violations.instalments.lapse-interval:PT1H}",
                initialDelayString = "${itms.violations.instalments.lapse-initial-delay:PT2M}")
        public void run() {
            int late = 0;
            for (String number : instalments.lapsedPlans()) {
                try {
                    late += instalments.markLateIfLapsed(number) ? 1 : 0;
                } catch (RuntimeException e) {
                    LOG.warn("Instalment plan {} not checked: {}", number, e.getClass().getSimpleName());
                }
            }
            if (late > 0) {
                LOG.info("Instalment plans late: {} (VF-073)", late);
            }
        }
    }

    @Bean
    ObjectionService objectionService(JdbcClient jdbc, ViolationService violations, AuditRecorder audit,
                                      DomainEventRecorder events, Clock clock, ViolationProperties properties) {
        return new ObjectionService(new JdbcObjectionRepository(jdbc), new JdbcCrossborderLinks(jdbc), violations,
                audit, events, clock, properties.objectionWindow());
    }

    /** VF-030 (Q-072 (a)): {@code itms.violations.points-at} CONFIRMATION (default) or RECORDING. */
    private static ViolationService.Settings.PointsAt pointsAt(Environment environment) {
        String value = environment.getProperty("itms.violations.points-at", "CONFIRMATION");
        try {
            return ViolationService.Settings.PointsAt.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("itms.violations.points-at must be CONFIRMATION or RECORDING", e);
        }
    }

    /** TR-032: rental-service lookup, switchable with {@code itms.violations.rental-transfer-enabled} (default on). */
    private static gov.itms.violation.application.Ports.Rentals rentals(Environment environment, Clock clock,
                                                                         ObservationRegistry registry) {
        if (!environment.getProperty("itms.violations.rental-transfer-enabled", Boolean.class, true)) {
            return (plate, at) -> java.util.Optional.empty();
        }
        return new Adapters.RemoteRentals(ServiceClients.create(client(environment, "rentals"), clock, registry));
    }

    private static UnpaidHold.Policy unpaidPolicy(boolean enabled, Duration after, int batchSize) {
        try {
            return new UnpaidHold.Policy(enabled, after, batchSize);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("itms.violations.unpaid-hold-*: " + e.getMessage(), e);
        }
    }

    private static ServiceClientProperties client(Environment environment, String name) {
        return Binder.get(environment).bind("itms.clients." + name, ServiceClientProperties.class)
                .orElseThrow(() -> new IllegalStateException("itms.clients." + name + " is not configured"))
                .validate("itms.clients." + name);
    }

    /** Runs the unpaid-violation holds (VF-023) when switched on; safe on every instance (rows are locked). */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "itms.violations.unpaid-hold-enabled", havingValue = "true", matchIfMissing = true)
    static class UnpaidHoldJobConfiguration {

        @Bean
        UnpaidHoldJob unpaidHoldJob(ViolationService violations) {
            return new UnpaidHoldJob(violations);
        }
    }

    /** Confirms violations whose objection window has lapsed (VF-030); on by default, off at RECORDING. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "itms.violations.points-at", havingValue = "CONFIRMATION", matchIfMissing = true)
    static class ConfirmationJobConfiguration {

        @Bean
        ConfirmationJob confirmationJob(ViolationService violations) {
            return new ConfirmationJob(violations);
        }
    }

    /** Adds the black points of confirmed violations (VF-030, UAT-1); a failure is retried on the next run. */
    public static class ConfirmationJob {

        private static final Logger LOG = LoggerFactory.getLogger(ConfirmationJob.class);
        private final ViolationService violations;

        public ConfirmationJob(ViolationService violations) {
            this.violations = violations;
        }

        @Scheduled(fixedDelayString = "${itms.violations.confirmation-interval:PT15M}",
                initialDelayString = "${itms.violations.confirmation-initial-delay:PT1M}")
        public void run() {
            int confirmed = 0;
            for (String number : violations.dueForConfirmation()) {
                try {
                    confirmed += violations.confirmIfDue(number) ? 1 : 0;
                } catch (RuntimeException e) {
                    LOG.warn("Violation {} not confirmed: {}", number, e.getClass().getSimpleName());
                }
            }
            if (confirmed > 0) {
                LOG.info("Violations confirmed, black points added: {} (VF-030)", confirmed);
            }
        }
    }

    /** Places holds on overdue violations and lifts those of paid or cancelled ones; a failure is retried next run. */
    public static class UnpaidHoldJob {

        private static final Logger LOG = LoggerFactory.getLogger(UnpaidHoldJob.class);
        private final ViolationService violations;

        public UnpaidHoldJob(ViolationService violations) {
            this.violations = violations;
        }

        @Scheduled(fixedDelayString = "${itms.violations.unpaid-hold-interval:PT1H}",
                initialDelayString = "${itms.violations.unpaid-hold-initial-delay:PT1M}")
        public void run() {
            int placed = 0;
            int lifted = 0;
            for (String number : violations.overdueForHold()) {
                try {
                    placed += violations.holdIfOverdue(number) ? 1 : 0;
                } catch (RuntimeException e) {
                    LOG.warn("Unpaid hold for {} not placed: {}", number, e.getClass().getSimpleName());
                }
            }
            for (String number : violations.settledWithHold()) {
                try {
                    lifted += violations.liftIfSettled(number) ? 1 : 0;
                } catch (RuntimeException e) {
                    LOG.warn("Unpaid hold of {} not lifted: {}", number, e.getClass().getSimpleName());
                }
            }
            if (placed + lifted > 0) {
                LOG.info("Unpaid violation holds: {} placed, {} lifted (VF-023)", placed, lifted);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "itms.violations.consumer-enabled", havingValue = "true")
    static class ConsumerConfiguration {

        @Bean
        Adapters.PaymentEventListener paymentEventListener(ViolationService violations,
                                                           InstalmentService instalments, ObjectMapper json) {
            return new Adapters.PaymentEventListener(violations, instalments, json);
        }

        @Bean
        Adapters.CrossborderEventListener crossborderEventListener(CrossborderViolations crossborder,
                                                                   ObjectionService objections, ObjectMapper json) {
            return new Adapters.CrossborderEventListener(crossborder, objections, json);
        }
    }
}

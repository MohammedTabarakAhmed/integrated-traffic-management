package gov.itms.violation.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.client.ServiceClientProperties;
import gov.itms.platform.client.ServiceClients;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.violation.application.ViolationService;
import gov.itms.violation.domain.BlackPoints;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
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
import tools.jackson.databind.ObjectMapper;

/** Wires violations to PostgreSQL, payment-service, hold-eligibility-service and the payment events. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ViolationServiceConfiguration.ViolationProperties.class)
public class ViolationServiceConfiguration {

    /**
     * {@code itms.violations.*} (NFR-042): black point rules (VF-031..VF-033; BRD defaults 12 months, 24 points,
     * 3/6/12 months, warnings at 12 and 18), cancellation reasons (VF-021), currency, business zone, how old a reported
     * violation may be, and what a suspension hold blocks.
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
                                      List<String> suspensionLiftableByRoles) {
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

    @Bean
    ViolationService violationService(JdbcClient jdbc, ObjectMapper json, ViolationProperties p,
                                      Environment environment, AuditRecorder audit, DomainEventRecorder events,
                                      Clock clock, ObjectProvider<ObservationRegistry> observations) {
        ObservationRegistry registry = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        return new ViolationService(new JdbcViolationRepository(jdbc, json),
                new Adapters.RemotePayables(ServiceClients.create(client(environment, "payments"), clock, registry)),
                new Adapters.RemoteHolds(ServiceClients.create(client(environment, "holds"), clock, registry),
                        p.suspensionBlockedServices(), p.suspensionLiftableByRoles()),
                audit, events,
                new ViolationService.Settings(new BlackPoints.Policy(p.pointsExpiryMonths(), p.suspensionThreshold(),
                        p.suspensionMonths(), p.warningThresholds()), Set.copyOf(p.cancelReasons()), p.currency(),
                        ZoneId.of(p.zone()), p.maxReportDelay()), clock);
    }

    private static ServiceClientProperties client(Environment environment, String name) {
        return Binder.get(environment).bind("itms.clients." + name, ServiceClientProperties.class)
                .orElseThrow(() -> new IllegalStateException("itms.clients." + name + " is not configured"))
                .validate("itms.clients." + name);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "itms.violations.consumer-enabled", havingValue = "true")
    static class ConsumerConfiguration {

        @Bean
        Adapters.PaymentEventListener paymentEventListener(ViolationService violations, ObjectMapper json) {
            return new Adapters.PaymentEventListener(violations, json);
        }
    }
}

package gov.itms.vehicle.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.client.ServiceClientProperties;
import gov.itms.platform.client.ServiceClients;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.vehicle.application.PlateService;
import gov.itms.vehicle.application.RequestDesk;
import gov.itms.vehicle.application.ServiceRules;
import gov.itms.vehicle.application.VehicleRules;
import gov.itms.vehicle.application.VehicleService;
import gov.itms.vehicle.application.VehicleTransactions;
import gov.itms.vehicle.domain.Records;
import gov.itms.vehicle.domain.Registration;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Wires vehicles to PostgreSQL, the insurers, fees-service, payment-service and the payment events. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({VehicleServiceConfiguration.VehicleProperties.class,
        VehicleServiceConfiguration.ServiceProperties.class})
public class VehicleServiceConfiguration {

    /**
     * {@code itms.vehicles.*} (NFR-042). Code-keyed maps are lists of objects (Spring drops '_' from map keys).
     */
    @ConfigurationProperties("itms.vehicles")
    public record VehicleProperties(
            List<CategoryRule> categories,
            @DefaultValue("12") int defaultValidityMonths,
            @DefaultValue("0") int oldVehicleYears,
            @DefaultValue("12") int oldVehicleValidityMonths,
            @DefaultValue("0") int defaultInspectionExemptYears,
            @DefaultValue("30") int inspectionValidityDays,
            @DefaultValue("30") int renewalWindowDays,
            @DefaultValue("VEH_FIRST_REG") String firstRegistrationFee,
            @DefaultValue("VEH_RENEWAL") String renewalFee,
            List<String> checkDigitRegions,
            @DefaultValue Insurance insurance) {

        /** One vehicle category's rules (VL-004, VL-023, VL-041). */
        public record CategoryRule(String code, Integer validityMonths, Integer inspectionExemptYears,
                                   String plateCategory) {
        }

        public record Insurance(URI baseUrl, @DefaultValue("3s") Duration timeout) {
        }

        public VehicleProperties {
            categories = categories == null ? List.of() : List.copyOf(categories);
            checkDigitRegions = checkDigitRegions == null ? List.of() : List.copyOf(checkDigitRegions);
            if (defaultValidityMonths < 1 || defaultValidityMonths > 60 || oldVehicleValidityMonths < 1
                    || oldVehicleValidityMonths > 60 || oldVehicleYears < 0 || oldVehicleYears > 100) {
                throw new IllegalArgumentException("itms.vehicles validity months must be 1-60 (VL-023)");
            }
            if (renewalWindowDays < 1 || renewalWindowDays > 180 || inspectionValidityDays < 1
                    || inspectionValidityDays > 365 || defaultInspectionExemptYears < 0
                    || defaultInspectionExemptYears > 50) {
                throw new IllegalArgumentException("itms.vehicles windows are out of range (VL-011, VL-041)");
            }
            for (CategoryRule c : categories) {
                if (c.code() == null || !c.code().matches("^[A-Z0-9][A-Z0-9_]{0,39}$")
                        || (c.validityMonths() != null && (c.validityMonths() < 1 || c.validityMonths() > 60))
                        || (c.inspectionExemptYears() != null
                        && (c.inspectionExemptYears() < 0 || c.inspectionExemptYears() > 50))) {
                    throw new IllegalArgumentException("itms.vehicles.categories has an invalid entry (VL-004)");
                }
            }
            if (insurance == null || insurance.baseUrl() == null) {
                throw new IllegalArgumentException("itms.vehicles.insurance.base-url is required (VL-024)");
            }
        }

        VehicleRules rules() {
            Map<String, Integer> validity = new java.util.HashMap<>();
            Map<String, Integer> exempt = new java.util.HashMap<>();
            Map<String, String> plates = new java.util.HashMap<>();
            for (CategoryRule c : categories) {
                if (c.validityMonths() != null) {
                    validity.put(c.code(), c.validityMonths());
                }
                if (c.inspectionExemptYears() != null) {
                    exempt.put(c.code(), c.inspectionExemptYears());
                }
                if (c.plateCategory() != null) {
                    plates.put(c.code(), c.plateCategory());
                }
            }
            return new VehicleRules(validity, defaultValidityMonths, oldVehicleYears, oldVehicleValidityMonths,
                    exempt, defaultInspectionExemptYears, inspectionValidityDays, renewalWindowDays, plates,
                    firstRegistrationFee, renewalFee, checkDigitRegions);
        }
    }

    /**
     * {@code itms.vehicles.services.*}: the Increment 2 transactions (ADR-059, Q-075). BRD defaults: reservation 10
     * working days (VL-033), retention 12 months (VL-039).
     */
    @ConfigurationProperties("itms.vehicles.services")
    public record ServiceProperties(
            Map<String, String> fees,
            @DefaultValue("10") int reservationWorkingDays,
            List<DayOfWeek> weekend,
            @DefaultValue("12") int retentionMonths,
            @DefaultValue("TRIAL") String trialPlateCategory,
            @DefaultValue("30") int trialPlateMaxDays,
            @DefaultValue("90") int permitMaxDays,
            @DefaultValue("true") boolean requireCustomsCertificate,
            List<ApprovalProperty> approvals,
            @DefaultValue("REV_PLATE_AUCTION") String auctionFinancialItem,
            @DefaultValue("AUTHORITY") String auctionBeneficiary,
            @DefaultValue("XTS") String currency,
            @DefaultValue Expiry expiry) {

        public record ApprovalProperty(String category, String bodyType, Records.ApprovalKind kind) {
        }

        public record Expiry(@DefaultValue("true") boolean enabled, @DefaultValue("15m") Duration interval) {
        }

        public ServiceProperties {
            fees = fees == null ? Map.of() : Map.copyOf(fees);
            weekend = weekend == null ? List.of() : List.copyOf(weekend);
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
            if (reservationWorkingDays < 1 || reservationWorkingDays > 60 || retentionMonths < 1
                    || retentionMonths > 120 || trialPlateMaxDays < 1 || trialPlateMaxDays > 366 || permitMaxDays < 1
                    || permitMaxDays > 366 || weekend.size() > 3) {
                throw new IllegalArgumentException("itms.vehicles.services periods are out of range (VL-016, VL-033,"
                        + " VL-038, VL-039)");
            }
            for (Registration.Kind kind : Registration.Kind.values()) {
                if (kind != Registration.Kind.FIRST && kind != Registration.Kind.RENEWAL
                        && kind != Registration.Kind.AUCTION && !fees.containsKey(kind.name())) {
                    throw new IllegalArgumentException("itms.vehicles.services.fees." + kind.name() + " is required");
                }
            }
            for (ApprovalProperty a : approvals) {
                if (a.kind() == null || (a.category() == null) == (a.bodyType() == null)) {
                    throw new IllegalArgumentException("itms.vehicles.services.approvals entries name a kind and"
                            + " either a category or a body type (VL-018, VL-019)");
                }
            }
        }

        ServiceRules rules() {
            Map<Registration.Kind, String> byKind = new java.util.EnumMap<>(Registration.Kind.class);
            fees.forEach((k, v) -> byKind.put(Registration.Kind.valueOf(k), v));
            return new ServiceRules(byKind, reservationWorkingDays, java.util.Set.copyOf(weekend), retentionMonths,
                    trialPlateCategory, trialPlateMaxDays, permitMaxDays, requireCustomsCertificate,
                    approvals.stream().map(a -> new ServiceRules.ApprovalRule(a.category(), a.bodyType(), a.kind()))
                            .toList(), auctionFinancialItem, auctionBeneficiary, currency);
        }
    }

    @Bean
    JdbcVehicleRepository jdbcVehicleRepository(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcVehicleRepository(jdbc, json);
    }

    @Bean
    JdbcRecordsRepository jdbcRecordsRepository(JdbcClient jdbc, ObjectMapper json, JdbcVehicleRepository vehicles) {
        return new JdbcRecordsRepository(jdbc, json, vehicles);
    }

    @Bean
    Adapters.HttpInsuranceRegister insuranceRegister(RestClient.Builder builder, VehicleProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.insurance().timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.insurance().timeout());
        RestClient insurer = builder.clone().baseUrl(properties.insurance().baseUrl().toString())
                .requestFactory(factory).build();
        return new Adapters.HttpInsuranceRegister(insurer);
    }

    @Bean
    RequestDesk requestDesk(JdbcVehicleRepository repo, JdbcRecordsRepository records, VehicleProperties properties,
                            ServiceProperties services, Environment environment, AuditRecorder audit, Clock clock,
                            ObjectProvider<ObservationRegistry> observations) {
        ObservationRegistry registry = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        return new RequestDesk(repo, records,
                new Adapters.RemoteFeeQuotes(ServiceClients.create(client(environment, "fees"), clock, registry)),
                new Adapters.RemotePayables(ServiceClients.create(client(environment, "payments"), clock, registry)),
                new Adapters.RemoteEligibility(ServiceClients.create(client(environment, "holds"), clock, registry)),
                properties.rules(), services.rules(), audit, clock);
    }

    @Bean
    VehicleService vehicleService(JdbcVehicleRepository repo, JdbcRecordsRepository records, RequestDesk desk,
                                  Adapters.HttpInsuranceRegister insurer, VehicleProperties properties,
                                  Environment environment, AuditRecorder audit, DomainEventRecorder events,
                                  Clock clock, ObjectProvider<ObservationRegistry> observations) {
        ObservationRegistry registry = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        return new VehicleService(repo, insurer,
                new Adapters.RemoteFeeQuotes(ServiceClients.create(client(environment, "fees"), clock, registry)),
                new Adapters.RemotePayables(ServiceClients.create(client(environment, "payments"), clock, registry)),
                new Adapters.RemoteEligibility(ServiceClients.create(client(environment, "holds"), clock, registry)),
                records, desk, properties.rules(), audit, events, clock);
    }

    @Bean
    VehicleTransactions vehicleTransactions(JdbcVehicleRepository repo, JdbcRecordsRepository records,
                                            Adapters.HttpInsuranceRegister insurer, RequestDesk desk,
                                            VehicleService vehicles, AuditRecorder audit, DomainEventRecorder events,
                                            Clock clock) {
        VehicleTransactions transactions = new VehicleTransactions(repo, records, insurer, desk, vehicles, audit,
                events, clock);
        vehicles.addHandler(transactions);
        return transactions;
    }

    @Bean
    PlateService plateService(JdbcVehicleRepository repo, JdbcRecordsRepository records, RequestDesk desk,
                              VehicleService vehicles, AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        PlateService plates = new PlateService(repo, records, desk, audit, events, clock);
        vehicles.addHandler(plates);
        return plates;
    }

    /** Ends unpaid reservations, retentions and trial plates whose period is over (VL-033, VL-038, VL-039). */
    public static class PlateExpiryJob {

        private static final Logger LOG = LoggerFactory.getLogger(PlateExpiryJob.class);
        private final PlateService plates;

        public PlateExpiryJob(PlateService plates) {
            this.plates = plates;
        }

        @Scheduled(initialDelayString = "${itms.vehicles.services.expiry.interval:15m}",
                fixedDelayString = "${itms.vehicles.services.expiry.interval:15m}")
        public void run() {
            try {
                int n = plates.expire();
                if (n > 0) {
                    LOG.info("Plate expiry: {} plates changed", n);
                }
            } catch (RuntimeException e) {
                LOG.error("Plate expiry run failed; it is retried on the next run", e);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "itms.vehicles.services.expiry.enabled", havingValue = "true",
            matchIfMissing = true)
    static class ExpiryConfiguration {

        @Bean
        PlateExpiryJob plateExpiryJob(PlateService plates) {
            return new PlateExpiryJob(plates);
        }
    }

    private static ServiceClientProperties client(Environment environment, String name) {
        return Binder.get(environment).bind("itms.clients." + name, ServiceClientProperties.class)
                .orElseThrow(() -> new IllegalStateException("itms.clients." + name + " is not configured"))
                .validate("itms.clients." + name);
    }

    /** Completes registrations from payment events (off without a broker). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "itms.vehicles.consumer-enabled", havingValue = "true")
    static class ConsumerConfiguration {

        @Bean
        Adapters.PaymentEventListener paymentEventListener(VehicleService vehicles, ObjectMapper json) {
            return new Adapters.PaymentEventListener(vehicles, json);
        }
    }
}

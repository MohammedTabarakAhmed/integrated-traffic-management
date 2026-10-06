package gov.itms.vehicle.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.client.ServiceClientProperties;
import gov.itms.platform.client.ServiceClients;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.vehicle.application.VehicleRules;
import gov.itms.vehicle.application.VehicleService;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Wires vehicles to PostgreSQL, the insurers, fees-service, payment-service and the payment events. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VehicleServiceConfiguration.VehicleProperties.class)
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

    @Bean
    VehicleService vehicleService(JdbcClient jdbc, ObjectMapper json, RestClient.Builder builder,
                                  VehicleProperties properties, Environment environment, AuditRecorder audit,
                                  DomainEventRecorder events, Clock clock,
                                  ObjectProvider<ObservationRegistry> observations) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.insurance().timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.insurance().timeout());
        RestClient insurer = builder.clone().baseUrl(properties.insurance().baseUrl().toString())
                .requestFactory(factory).build();
        ObservationRegistry registry = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        return new VehicleService(new JdbcVehicleRepository(jdbc, json), new Adapters.HttpInsuranceRegister(insurer),
                new Adapters.RemoteFeeQuotes(ServiceClients.create(client(environment, "fees"), clock, registry)),
                new Adapters.RemotePayables(ServiceClients.create(client(environment, "payments"), clock, registry)),
                properties.rules(), audit, events, clock);
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

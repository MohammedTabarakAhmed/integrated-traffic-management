package gov.itms.licensing.infrastructure;

import gov.itms.licensing.application.LicenceClassService;
import gov.itms.licensing.application.LicenceNumberService;
import gov.itms.licensing.application.LicenceService;
import gov.itms.licensing.application.LicensingRules;
import gov.itms.licensing.domain.LicenceRequest;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.client.ServiceClientProperties;
import gov.itms.platform.client.ServiceClients;
import gov.itms.platform.events.DomainEventRecorder;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

/** Wires licensing to PostgreSQL, fees-service, payment-service, hold-eligibility-service and the payment events. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LicensingServiceConfiguration.LicensingProperties.class)
public class LicensingServiceConfiguration {

    /**
     * {@code itms.licensing.*} (NFR-042). Code-keyed maps are written with brackets ({@code "[ISSUE]": LIC_ISSUE}).
     *
     * @param probationMonths BRD default 2 years (DL-050)
     * @param serviceCodes    fees-service and hold-eligibility-service code per paid transaction
     * @param conditionCodes  the licence conditions that may be recorded (DL-023)
     */
    @ConfigurationProperties("itms.licensing")
    public record LicensingProperties(@DefaultValue("24") int probationMonths, Map<String, String> serviceCodes,
                                      List<String> conditionCodes) {

        public LicensingProperties {
            serviceCodes = serviceCodes == null ? Map.of() : Map.copyOf(serviceCodes);
            conditionCodes = conditionCodes == null ? List.of() : List.copyOf(conditionCodes);
            for (String c : conditionCodes) {
                if (c == null || !c.matches("^[A-Z][A-Z0-9_]{0,39}$")) {
                    throw new IllegalArgumentException("itms.licensing.condition-codes has an invalid entry (DL-023)");
                }
            }
        }

        LicensingRules rules() {
            Map<LicenceRequest.Kind, String> byKind = new EnumMap<>(LicenceRequest.Kind.class);
            serviceCodes.forEach((k, v) -> byKind.put(LicenceRequest.Kind.valueOf(k), v));
            return new LicensingRules(probationMonths, byKind, Set.copyOf(conditionCodes));
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

    private static ServiceClientProperties client(Environment environment, String name) {
        return Binder.get(environment).bind("itms.clients." + name, ServiceClientProperties.class)
                .orElseThrow(() -> new IllegalStateException("itms.clients." + name + " is not configured"))
                .validate("itms.clients." + name);
    }

    /** Completes paid requests from payment events (off without a broker). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "itms.licensing.consumer-enabled", havingValue = "true")
    static class ConsumerConfiguration {

        @Bean
        Adapters.PaymentEventListener paymentEventListener(LicenceService licences, ObjectMapper json) {
            return new Adapters.PaymentEventListener(licences, json);
        }
    }
}

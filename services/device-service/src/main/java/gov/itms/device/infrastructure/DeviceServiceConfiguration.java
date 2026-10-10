package gov.itms.device.infrastructure;

import gov.itms.device.application.DeviceService;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.client.ServiceClientProperties;
import gov.itms.platform.client.ServiceClients;
import gov.itms.platform.events.DomainEventRecorder;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/** Wires device PostgreSQL storage and the violation-service boundary. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeviceServiceConfiguration.DeviceProperties.class)
public class DeviceServiceConfiguration {

    @ConfigurationProperties("itms.devices")
    public record DeviceProperties(@DefaultValue("PT5M") Duration maximumLag) {
        public DeviceProperties {
            if (maximumLag.isNegative() || maximumLag.isZero() || maximumLag.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("itms.devices.maximum-lag must be between 1 second and 5 minutes");
            }
        }
    }

    @Bean
    JdbcDeviceRepository deviceRepository(JdbcClient jdbc) {
        return new JdbcDeviceRepository(jdbc);
    }

    @Bean
    DeviceService deviceService(JdbcDeviceRepository repo, DeviceProperties properties, Environment environment,
                                AuditRecorder audit, DomainEventRecorder events, Clock clock,
                                ObjectProvider<ObservationRegistry> observations) {
        ObservationRegistry registry = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        ServiceClientProperties client = Binder.get(environment)
                .bind("itms.clients.violations", ServiceClientProperties.class)
                .orElseThrow(() -> new IllegalStateException("itms.clients.violations is not configured"))
                .validate("itms.clients.violations");
        RestClient violations = ServiceClients.create(client, clock, registry);
        return new DeviceService(repo, new Adapters.RemoteViolationRecorder(violations), audit, events, clock,
                properties.maximumLag());
    }
}

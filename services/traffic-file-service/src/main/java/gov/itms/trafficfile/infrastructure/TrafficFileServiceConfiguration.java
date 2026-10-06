package gov.itms.trafficfile.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.trafficfile.application.IdentityRegister;
import gov.itms.trafficfile.application.TrafficFileService;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Wires the application layer to its JDBC and register adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TrafficFileProperties.class)
public class TrafficFileServiceConfiguration {

    @Bean
    JdbcTrafficFileRepository trafficFileRepository(JdbcClient jdbc, ObjectMapper json,
                                                    TrafficFileProperties properties) {
        return new JdbcTrafficFileRepository(jdbc, json, properties.fileNumberPrefix(), properties.fileNumberWidth());
    }

    @Bean
    IdentityRegister identityRegister(RestClient.Builder builder, TrafficFileProperties properties) {
        TrafficFileProperties.Register register = properties.register();
        HttpClient http = HttpClient.newBuilder().connectTimeout(register.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(register.timeout());
        RestClient client = builder.clone().baseUrl(register.baseUrl().toString()).requestFactory(factory).build();
        return new HttpIdentityRegister(client, register.genderCodes(), register.residencyCodes());
    }

    @Bean
    TrafficFileService trafficFileService(JdbcTrafficFileRepository files, IdentityRegister register,
                                          AuditRecorder audit, DomainEventRecorder events, Clock clock,
                                          TrafficFileProperties properties) {
        return new TrafficFileService(files, register, audit, events, clock, properties.nationalIdRegex());
    }
}

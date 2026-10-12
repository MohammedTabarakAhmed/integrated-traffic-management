package gov.itms.trafficfile.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.events.ProcessedEvents;
import gov.itms.trafficfile.application.BanListService;
import gov.itms.trafficfile.application.CommercialRegister;
import gov.itms.trafficfile.application.CompanyFileService;
import gov.itms.trafficfile.application.DuplicateMergeService;
import gov.itms.trafficfile.application.IdentityRegister;
import gov.itms.trafficfile.application.NameSearchService;
import gov.itms.trafficfile.application.RegisterSyncService;
import gov.itms.trafficfile.application.TrafficFileService;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Wires the application layer to its JDBC and register adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TrafficFileProperties.class, NameSearchProperties.class})
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
    CommercialRegister commercialRegister(RestClient.Builder builder, TrafficFileProperties properties) {
        TrafficFileProperties.Licensing licensing = properties.licensing();
        Map<String, RestClient> clients = new HashMap<>();
        licensing.authorities().forEach((source, url) -> {
            HttpClient http = HttpClient.newBuilder().connectTimeout(licensing.timeout()).build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(licensing.timeout());
            clients.put(source, builder.clone().baseUrl(url.toString()).requestFactory(factory).build());
        });
        return new HttpCommercialRegister(clients);
    }

    @Bean
    CompanyFileService companyFileService(JdbcTrafficFileRepository files, CommercialRegister register,
                                          AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        return new CompanyFileService(files, register, audit, events, clock);
    }

    @Bean
    DuplicateMergeService duplicateMergeService(JdbcTrafficFileRepository files, AuditRecorder audit,
                                                DomainEventRecorder events, Clock clock,
                                                TrafficFileProperties properties) {
        return new DuplicateMergeService(files, audit, events, clock, properties.duplicateNameThreshold());
    }

    @Bean
    RegisterSyncService registerSyncService(JdbcTrafficFileRepository files, IdentityRegister register,
                                            ProcessedEvents processed, AuditRecorder audit,
                                            DomainEventRecorder events, Clock clock,
                                            TrafficFileProperties properties) {
        return new RegisterSyncService(files, register, processed, audit, events, clock,
                properties.nationalIdRegex());
    }

    @Bean
    JdbcBanRepository banRepository(JdbcClient jdbc) {
        return new JdbcBanRepository(jdbc);
    }

    @Bean
    BanListService banListService(JdbcBanRepository bans, JdbcTrafficFileRepository files, AuditRecorder audit,
                                  DomainEventRecorder events, Clock clock) {
        return new BanListService(bans, files, audit, events, clock);
    }

    @Bean
    TrafficFileService trafficFileService(JdbcTrafficFileRepository files, JdbcBanRepository bans,
                                          IdentityRegister register, AuditRecorder audit, DomainEventRecorder events,
                                          Clock clock, TrafficFileProperties properties) {
        return new TrafficFileService(files, bans, register, audit, events, clock, properties.nationalIdRegex());
    }

    @Bean
    NameSearchService nameSearchService(JdbcClient jdbc, AuditRecorder audit, NameSearchProperties properties) {
        return new NameSearchService(new JdbcNameIndex(jdbc), audit, properties.maxResults(),
                properties.minKeyLength());
    }
}

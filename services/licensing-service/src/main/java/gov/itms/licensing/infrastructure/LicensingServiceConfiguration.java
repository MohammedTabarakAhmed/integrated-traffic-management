package gov.itms.licensing.infrastructure;

import gov.itms.licensing.application.LicenceClassService;
import gov.itms.licensing.application.LicenceNumberService;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires licensing to PostgreSQL. */
@Configuration(proxyBeanMethods = false)
public class LicensingServiceConfiguration {

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
}

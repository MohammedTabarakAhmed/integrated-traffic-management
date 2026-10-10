package gov.itms.permit.infrastructure;

import gov.itms.permit.application.PermitService;
import gov.itms.permit.application.Ports;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class PermitConfiguration {
    @Bean Ports.PermitRepository permitRepository(JdbcClient jdbc) { return new JdbcPermitRepository(jdbc); }
    @Bean PermitService permitService(Ports.PermitRepository repo, AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        return new PermitService(repo, audit, events, clock);
    }
}

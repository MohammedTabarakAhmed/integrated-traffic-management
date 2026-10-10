package gov.itms.marine.infrastructure;

import gov.itms.marine.application.MarineService;
import gov.itms.marine.application.Ports;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods=false)
public class MarineConfiguration {
    @Bean Ports.MarineRepository marineRepository(JdbcClient jdbc){return new JdbcMarineRepository(jdbc);}
    @Bean MarineService marineService(Ports.MarineRepository repo,AuditRecorder audit,DomainEventRecorder events,Clock clock){return new MarineService(repo,audit,events,clock);}
}

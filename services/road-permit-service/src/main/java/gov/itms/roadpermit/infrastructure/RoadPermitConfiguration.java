package gov.itms.roadpermit.infrastructure;

import gov.itms.roadpermit.application.RoadPermitService;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods=false)
public class RoadPermitConfiguration {
    @Bean RoadPermitService.Repository roadPermitRepository(JdbcClient jdbc){return new JdbcRoadPermitRepository(jdbc);}
    @Bean RoadPermitService roadPermitService(RoadPermitService.Repository repo,AuditRecorder audit,DomainEventRecorder events,Clock clock){return new RoadPermitService(repo,audit,events,clock);}
}

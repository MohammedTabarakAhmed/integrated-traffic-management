package gov.itms.accident.infrastructure;

import gov.itms.accident.application.AccidentService;
import gov.itms.accident.application.Ports;
import gov.itms.accident.application.AccidentWorkflowService;
import gov.itms.accident.application.WorkflowPorts;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccidentConfiguration.AccidentProperties.class)
public class AccidentConfiguration {
    @ConfigurationProperties("itms.accidents")
    public record AccidentProperties(@DefaultValue("P30D") Duration objectionWindow) { }
    @Bean Ports.AccidentRepository accidentRepository(JdbcClient jdbc) {
        return new JdbcAccidentRepository(jdbc);
    }
    @Bean AccidentService accidentService(Ports.AccidentRepository repo, AuditRecorder audit,
                                           DomainEventRecorder events, Clock clock) {
        return new AccidentService(repo, audit, events, clock);
    }
    @Bean WorkflowPorts.AccidentWorkflowRepository accidentWorkflowRepository(JdbcClient jdbc) {
        return new JdbcAccidentWorkflowRepository(jdbc);
    }
    @Bean AccidentWorkflowService accidentWorkflowService(Ports.AccidentRepository accidents,
            WorkflowPorts.AccidentWorkflowRepository workflow,AuditRecorder audit,DomainEventRecorder events,
            ObjectMapper json,Clock clock,AccidentProperties properties) {
        return new AccidentWorkflowService(accidents,workflow,audit,events,json,clock,properties.objectionWindow());
    }
}

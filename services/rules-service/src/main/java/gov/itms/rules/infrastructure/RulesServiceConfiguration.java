package gov.itms.rules.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.rules.application.RuleService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires the application layer to its JDBC adapter. */
@Configuration(proxyBeanMethods = false)
public class RulesServiceConfiguration {

    @Bean
    JdbcRuleRepository ruleRepository(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcRuleRepository(jdbc, json);
    }

    @Bean
    RuleService ruleService(JdbcRuleRepository rules, AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        return new RuleService(rules, audit, events, clock);
    }
}

package gov.itms.rental.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.rental.application.Ports;
import gov.itms.rental.application.RentalService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class RentalConfiguration {
    @Bean
    Ports.RentalRepository rentalRepository(JdbcClient jdbc) {
        return new JdbcRentalRepository(jdbc);
    }

    @Bean
    RentalService rentalService(Ports.RentalRepository repo, AuditRecorder audit, DomainEventRecorder events,
                                Clock clock) {
        return new RentalService(repo, audit, events, clock);
    }
}

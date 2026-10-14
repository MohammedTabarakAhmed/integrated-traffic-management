package gov.itms.migration.infrastructure;

import gov.itms.migration.application.MigrationService;
import gov.itms.migration.application.Ports;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MigrationService.MigrationProperties.class)
public class MigrationConfiguration {
    /** One JDBC repository implements every migration port; registering it once avoids ambiguous injection. */
    @Bean
    JdbcMigrationRepository migrationRepository(JdbcClient jdbc) {
        return new JdbcMigrationRepository(jdbc);
    }

    @Bean
    Ports.DataAccessPort dataAccessPort(JdbcClient jdbc) {
        return new JdbcDataAccessPort(jdbc);
    }

    @Bean
    MigrationService migrationService(JdbcMigrationRepository repository, Ports.DataAccessPort dataAccess,
                                      AuditRecorder audit, DomainEventRecorder events,
                                      Clock clock, MigrationService.MigrationProperties properties) {
        return new MigrationService(repository, repository, repository, repository,
                repository, repository, repository, repository,
                dataAccess, audit, events, clock, properties);
    }
}
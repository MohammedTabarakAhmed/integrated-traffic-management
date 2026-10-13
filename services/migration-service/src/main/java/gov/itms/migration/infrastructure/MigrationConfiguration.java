package gov.itms.migration.infrastructure;

import gov.itms.migration.application.MigrationService;
import gov.itms.migration.application.Ports;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class MigrationConfiguration {
    @Bean
    Ports.LegacySystemRepository legacySystemRepository(JdbcClient jdbc) {
        return new JdbcMigrationRepository(jdbc);
    }

    @Bean
    Ports.EntityMappingRepository entityMappingRepository(JdbcClient jdbc) {
        return new JdbcMigrationRepository(jdbc);
    }

    @Bean
    Ports.MigrationJobRepository migrationJobRepository(JdbcClient jdbc) {
        return new JdbcMigrationRepository(jdbc);
    }

    @Bean
    Ports.MigrationExecutionRepository migrationExecutionRepository(JdbcClient jdbc) {
        return new JdbcMigrationRepository(jdbc);
    }

    @Bean
    Ports.ReconciliationRepository reconciliationRepository(JdbcClient jdbc) {
        return new JdbcMigrationRepository(jdbc);
    }

    @Bean
    Ports.ExceptionRepository exceptionRepository(JdbcClient jdbc) {
        return new JdbcMigrationRepository(jdbc);
    }

    @Bean
    Ports.RollbackPlanRepository rollbackPlanRepository(JdbcClient jdbc) {
        return new JdbcMigrationRepository(jdbc);
    }

    @Bean
    Ports.AnonymizationRepository anonymizationRepository(JdbcClient jdbc) {
        return new JdbcMigrationRepository(jdbc);
    }

    @Bean
    Ports.DataAccessPort dataAccessPort(JdbcClient jdbc) {
        return new JdbcDataAccessPort(jdbc);
    }

    @Bean
    MigrationService migrationService(Ports.LegacySystemRepository legacyRepo,
                                      Ports.EntityMappingRepository mappingRepo,
                                      Ports.MigrationJobRepository jobRepo,
                                      Ports.MigrationExecutionRepository execRepo,
                                      Ports.ReconciliationRepository reconRepo,
                                      Ports.ExceptionRepository exceptionRepo,
                                      Ports.RollbackPlanRepository rollbackRepo,
                                      Ports.AnonymizationRepository anonymizationRepo,
                                      Ports.DataAccessPort dataAccess,
                                      AuditRecorder audit, DomainEventRecorder events,
                                      Clock clock, MigrationService.MigrationProperties properties) {
        return new MigrationService(legacyRepo, mappingRepo, jobRepo, execRepo,
                reconRepo, exceptionRepo, rollbackRepo, anonymizationRepo,
                dataAccess, audit, events, clock, properties);
    }
}
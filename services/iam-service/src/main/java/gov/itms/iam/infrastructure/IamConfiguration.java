package gov.itms.iam.infrastructure;

import gov.itms.iam.application.IamService;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.AuthorisationResolver;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires the application layer to its JDBC adapters and supplies the local authorisation resolver. */
@Configuration(proxyBeanMethods = false)
public class IamConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IamConfiguration.class);

    @Bean
    IamService iamService(JdbcClient jdbc, AuditRecorder audit, Clock clock) {
        return new IamService(new JdbcPermissionRepository(jdbc), new JdbcRoleRepository(jdbc),
                new JdbcAssignmentRepository(jdbc), new JdbcApprovalLimitRepository(jdbc),
                new JdbcRoleConflictRepository(jdbc), audit, clock);
    }

    /** Replaces the platform's remote resolver: iam-service is the source of grants (ADR-022). */
    @Bean
    AuthorisationResolver localAuthorisationResolver(IamService service) {
        return new LocalAuthorisationResolver(service);
    }

    /**
     * One-time bootstrap of the first administrator (no default account is created, SEC-004): only acts when nobody
     * can manage assignments and a subject is configured for this environment.
     */
    @Bean
    ApplicationRunner iamBootstrapAdministrator(IamService service,
                                                @Value("${itms.iam.bootstrap-admin.subject:}") String subject,
                                                @Value("${itms.iam.bootstrap-admin.username:}") String username) {
        return args -> {
            if (subject.isBlank()) {
                return;
            }
            if (service.bootstrapAdministrator(subject, username.isBlank() ? null : username)) {
                log.warn("Bootstrap: assigned IT_ADMINISTRATOR (national scope) to the configured subject because no "
                        + "one could manage role assignments. This is audited as SYSTEM_BOOTSTRAP.");
            }
        };
    }
}

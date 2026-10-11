package gov.itms.violation.infrastructure;

import gov.itms.violation.application.Ports;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Test access to the package-private JDBC repository, so API tests can check the job queries against PostgreSQL. */
public final class RepositoryAccess {

    private RepositoryAccess() {
    }

    public static Ports.ViolationRepository violations(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcViolationRepository(jdbc, json);
    }
}

package gov.itms.platform.credentials;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Rotated database logins (SEC-004, ADR-039): watches {@code ${ITMS_SECRETS_DIR}/spring/datasource/} and applies a
 * new login to the connection pool. On by default; {@code itms.secrets.database-rotation.enabled=false} turns it off.
 * The check interval ({@code itms.secrets.database-rotation.check-interval}, default 10 s) must be 1 s to 5 min
 * (NFR-042).
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass(HikariDataSource.class)
@ConditionalOnProperty(name = "itms.secrets.database-rotation.enabled", havingValue = "true", matchIfMissing = true)
public class ItmsSecretsAutoConfiguration {

    static final Duration MIN_INTERVAL = Duration.ofSeconds(1);
    static final Duration MAX_INTERVAL = Duration.ofMinutes(5);

    @Bean
    @ConditionalOnBean(DataSource.class)
    DatabaseCredentialRotation itmsDatabaseCredentialRotation(
            DataSource dataSource,
            @Value("${ITMS_SECRETS_DIR:/run/secrets/}") String secretsDirectory,
            @Value("${itms.secrets.database-rotation.check-interval:10s}") Duration checkInterval)
            throws SQLException {
        if (checkInterval == null || checkInterval.compareTo(MIN_INTERVAL) < 0
                || checkInterval.compareTo(MAX_INTERVAL) > 0) {
            throw new IllegalArgumentException(
                    "itms.secrets.database-rotation.check-interval must be 1s to 5m (NFR-042)");
        }
        if (!dataSource.isWrapperFor(HikariDataSource.class)) {
            throw new IllegalStateException("database login rotation needs a Hikari pool; set "
                    + "itms.secrets.database-rotation.enabled=false for another pool");
        }
        return new DatabaseCredentialRotation(dataSource.unwrap(HikariDataSource.class), Path.of(secretsDirectory),
                checkInterval);
    }
}

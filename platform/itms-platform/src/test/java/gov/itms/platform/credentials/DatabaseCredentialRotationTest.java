package gov.itms.platform.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Rotated database logins (SEC-004, ADR-039) against a real PostgreSQL: logins are members of itms_owner, as the
 * secret store creates them (infra/local/openbao/configure.sh).
 */
@Testcontainers
class DatabaseCredentialRotationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @TempDir
    Path secrets;

    @BeforeAll
    static void owner() throws SQLException {
        admin("CREATE ROLE itms_owner NOLOGIN");
        admin("CREATE TABLE probe (id int); ALTER TABLE probe OWNER TO itms_owner");
    }

    private static void admin(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static void login(String name) throws SQLException {
        admin("CREATE ROLE \"" + name + "\" LOGIN PASSWORD 'pw-" + name + "' NOSUPERUSER INHERIT IN ROLE itms_owner");
    }

    private void writeLogin(String name) throws IOException {
        Path dir = Files.createDirectories(secrets.resolve("spring").resolve("datasource"));
        Files.writeString(dir.resolve("username"), name + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("password"), "pw-" + name + "\n", StandardCharsets.UTF_8);
    }

    private static HikariDataSource pool(String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(name);
        config.setPassword("pw-" + name);
        config.setConnectionInitSql("SET ROLE itms_owner");
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    private static String sessionUser(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             // reading itms_owner's table proves the login acts as the owner role
             ResultSet rs = st.executeQuery("select session_user || '/' || current_user "
                     + "from (select count(*) from probe) owned")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    @Tag("SEC-004")
    @DisplayName("SEC-004: a rotated login is applied without a restart; the old login can then be dropped")
    void appliesRotatedLogin() throws Exception {
        login("v-svc-1");
        login("v-svc-2");
        try (HikariDataSource ds = pool("v-svc-1")) {
            assertThat(sessionUser(ds)).isEqualTo("v-svc-1/itms_owner");
            DatabaseCredentialRotation rotation = new DatabaseCredentialRotation(ds, secrets, Duration.ofSeconds(1),
                    Duration.ofMillis(10));

            assertThat(rotation.check()).as("no files: nothing to do").isFalse();
            writeLogin("v-svc-1");
            assertThat(rotation.check()).as("same login: nothing to do").isFalse();

            writeLogin("v-svc-2");
            assertThat(rotation.check()).isTrue();
            assertThat(rotation.check()).as("applied once").isFalse();
            assertThat(sessionUser(ds)).isEqualTo("v-svc-2/itms_owner");

            // The old login's lease ends: its sessions are cut and it is dropped; the pool keeps working.
            admin("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'v-svc-1'");
            admin("DROP ROLE \"v-svc-1\"");
            for (int i = 0; i < 3; i++) {
                assertThat(sessionUser(ds)).isEqualTo("v-svc-2/itms_owner");
            }
        }
    }

    @Test
    @Tag("SEC-004")
    @DisplayName("SEC-004: an empty or half-present login is ignored; the pool keeps its current login")
    void ignoresIncompleteFiles() throws Exception {
        login("v-svc-3");
        try (HikariDataSource ds = pool("v-svc-3")) {
            DatabaseCredentialRotation rotation = new DatabaseCredentialRotation(ds, secrets, Duration.ofSeconds(1),
                    Duration.ofMillis(10));
            Path dir = Files.createDirectories(secrets.resolve("spring").resolve("datasource"));
            Files.writeString(dir.resolve("username"), "v-other", StandardCharsets.UTF_8);
            assertThat(rotation.check()).as("no password file").isFalse();
            Files.writeString(dir.resolve("password"), "  \n", StandardCharsets.UTF_8);
            assertThat(rotation.check()).as("empty password").isFalse();
            assertThat(sessionUser(ds)).isEqualTo("v-svc-3/itms_owner");
            assertThat(new DatabaseCredentialRotation.Login("u", "secret").toString()).doesNotContain("secret");
        }
    }

    @Test
    @Tag("SEC-004")
    @Tag("NFR-042")
    @DisplayName("NFR-042: the check interval must be 1 s to 5 min; rotation runs on a schedule once started")
    void intervalRangeAndSchedule() throws Exception {
        login("v-svc-4");
        login("v-svc-5");
        ItmsSecretsAutoConfiguration config = new ItmsSecretsAutoConfiguration();
        try (HikariDataSource ds = pool("v-svc-4")) {
            for (Duration bad : new Duration[] {Duration.ofMillis(999), Duration.ofMinutes(5).plusSeconds(1), null}) {
                assertThatThrownBy(() -> config.itmsDatabaseCredentialRotation(ds, secrets.toString(), bad))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("check-interval");
            }
            DatabaseCredentialRotation rotation = config.itmsDatabaseCredentialRotation(ds, secrets.toString(),
                    Duration.ofSeconds(1));
            rotation.start();
            try {
                assertThat(rotation.isRunning()).isTrue();
                writeLogin("v-svc-5");
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (!sessionUser(ds).startsWith("v-svc-5/") && System.nanoTime() < deadline) {
                    Thread.sleep(200);
                }
                assertThat(sessionUser(ds)).isEqualTo("v-svc-5/itms_owner");
            } finally {
                rotation.stop();
            }
            assertThat(rotation.isRunning()).isFalse();
        }
    }
}

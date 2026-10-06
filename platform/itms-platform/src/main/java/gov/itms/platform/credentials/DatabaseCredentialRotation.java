package gov.itms.platform.credentials;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Applies rotated database credentials without a restart (SEC-004, ADR-039). The secret store's agent writes the
 * service's short-lived login as two files, {@code spring/datasource/username} and {@code spring/datasource/password},
 * under the secrets directory (the same files Spring Boot reads at start-up, ADR-038). When they change, the pool
 * takes the new login and retires its connections: idle ones at once, busy ones when returned, so new connections
 * use the new login before the old one expires.
 * <p>
 * A pair is applied only when two reads a moment apart agree, so a half-written pair is never used. Nothing happens
 * when the files do not exist (tests, or a deployment with static credentials). Credentials are never logged.
 */
public final class DatabaseCredentialRotation implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCredentialRotation.class);

    /** One login: compared, never printed. */
    record Login(String username, String password) {

        @Override
        public String toString() {
            return "Login[username=" + username + ", password=******]";
        }
    }

    private final HikariDataSource dataSource;
    private final Path usernameFile;
    private final Path passwordFile;
    private final Duration checkInterval;
    private final Duration settle;
    private volatile Login current;
    private volatile ScheduledExecutorService scheduler;

    public DatabaseCredentialRotation(HikariDataSource dataSource, Path secretsDirectory, Duration checkInterval) {
        this(dataSource, secretsDirectory, checkInterval, Duration.ofMillis(200));
    }

    DatabaseCredentialRotation(HikariDataSource dataSource, Path secretsDirectory, Duration checkInterval,
                               Duration settle) {
        this.dataSource = Objects.requireNonNull(dataSource);
        Path dir = secretsDirectory.resolve("spring").resolve("datasource");
        this.usernameFile = dir.resolve("username");
        this.passwordFile = dir.resolve("password");
        this.checkInterval = checkInterval;
        this.settle = settle;
        this.current = new Login(dataSource.getUsername(), dataSource.getPassword());
    }

    /** Checks the files once; applies a new, stable login. Returns whether the pool's login changed. */
    boolean check() {
        Login read = read();
        if (read == null || read.equals(current)) {
            return false;
        }
        try {
            Thread.sleep(settle.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!read.equals(read())) {
            return false; // still being written: next check
        }
        var config = dataSource.getHikariConfigMXBean();
        config.setUsername(read.username());
        config.setPassword(read.password());
        var pool = dataSource.getHikariPoolMXBean();
        if (pool != null) {
            pool.softEvictConnections();
        }
        current = read;
        log.info("Database login rotated for pool {}: new connections use the new login", dataSource.getPoolName());
        return true;
    }

    private Login read() {
        if (!Files.isRegularFile(usernameFile) || !Files.isRegularFile(passwordFile)) {
            return null;
        }
        try {
            String username = Files.readString(usernameFile, StandardCharsets.UTF_8).strip();
            String password = Files.readString(passwordFile, StandardCharsets.UTF_8).strip();
            return username.isEmpty() || password.isEmpty() ? null : new Login(username, password);
        } catch (IOException e) {
            log.warn("Database login files could not be read; keeping the current login: {}", e.getClass().getName());
            return null;
        }
    }

    @Override
    public void start() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "itms-db-credential-rotation");
            t.setDaemon(true);
            return t;
        });
        long millis = checkInterval.toMillis();
        executor.scheduleWithFixedDelay(() -> {
            try {
                check();
            } catch (RuntimeException e) {
                log.warn("Database login check failed; retrying: {}", e.getClass().getName());
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
        scheduler = executor;
    }

    @Override
    public void stop() {
        ScheduledExecutorService executor = scheduler;
        if (executor != null) {
            executor.shutdownNow();
        }
        scheduler = null;
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}

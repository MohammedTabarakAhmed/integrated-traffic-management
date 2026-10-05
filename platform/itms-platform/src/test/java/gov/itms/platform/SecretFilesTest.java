package gov.itms.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Secrets as files (SEC-004, ADR-038), with the same import line every service has in its {@code application.yml}:
 * one file per property, named after it, read from {@code ITMS_SECRETS_DIR}. A secret file wins over the value in
 * configuration, a trailing new line is dropped, and a missing directory is not an error.
 */
class SecretFilesTest {

    private static final String IMPORT = "optional:configtree:${ITMS_SECRETS_DIR:/run/secrets/}";

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    private static Environment start(Map<String, Object> properties) {
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(properties);
        try (ConfigurableApplicationContext context = app.run()) {
            return context.getEnvironment();
        }
    }

    @Test
    @Tag("SEC-004")
    @DisplayName("SEC-004: a secret file named after a property supplies it and overrides configuration")
    void secretFilesSupplyProperties(@TempDir Path secrets) throws IOException {
        Files.writeString(secrets.resolve("spring.datasource.password"), "s3cret-from-file\n", StandardCharsets.UTF_8);
        Files.writeString(secrets.resolve("itms.clients.eligibility.client-secret"), "client-secret",
                StandardCharsets.UTF_8);

        Environment env = start(Map.of("spring.config.import", IMPORT,
                "ITMS_SECRETS_DIR", secrets.toString() + "/",
                "spring.datasource.password", "from-configuration"));

        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("s3cret-from-file");
        assertThat(env.getProperty("itms.clients.eligibility.client-secret")).isEqualTo("client-secret");
    }

    @Test
    @Tag("SEC-004")
    @DisplayName("SEC-004: without a secrets directory (tests, tools) the service still starts")
    void missingDirectoryIsOptional(@TempDir Path base) {
        Environment env = start(Map.of("spring.config.import", IMPORT,
                "ITMS_SECRETS_DIR", base.resolve("absent").toString() + "/",
                "spring.datasource.password", "from-configuration"));

        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("from-configuration");
    }
}

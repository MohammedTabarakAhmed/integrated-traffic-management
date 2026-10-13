package gov.itms.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** migration-service: data migration, reconciliation, legacy ID preservation and rollback (DAT-001..DAT-017). */
@SpringBootApplication(scanBasePackages={"gov.itms.migration","gov.itms.platform"})
public class MigrationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MigrationServiceApplication.class, args);
    }
}
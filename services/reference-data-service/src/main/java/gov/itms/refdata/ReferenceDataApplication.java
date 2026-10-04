package gov.itms.refdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** reference-data-service: one reference data manager for all code lists (CC-110). */
@SpringBootApplication
public class ReferenceDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReferenceDataApplication.class, args);
    }
}

package gov.itms.licensing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** licensing-service: licence classes, driving licences and their transactions (DL-020..DL-023, DL-050..DL-065). */
@SpringBootApplication
public class LicensingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LicensingServiceApplication.class, args);
    }
}

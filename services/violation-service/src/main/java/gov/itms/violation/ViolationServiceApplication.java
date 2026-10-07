package gov.itms.violation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** violation-service: versioned business rule tables, test runs and the decision log (CC-050..CC-053). */
@SpringBootApplication
public class ViolationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ViolationServiceApplication.class, args);
    }
}

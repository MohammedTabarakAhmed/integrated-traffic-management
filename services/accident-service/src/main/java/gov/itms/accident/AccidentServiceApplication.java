package gov.itms.accident;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Accident reporting, investigation, fault decisions and repair permits (AC-001..AC-023). */
@SpringBootApplication
public class AccidentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccidentServiceApplication.class, args);
    }
}

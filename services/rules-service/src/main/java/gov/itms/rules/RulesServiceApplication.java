package gov.itms.rules;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** rules-service: versioned business rule tables, test runs and the decision log (CC-050..CC-053). */
@SpringBootApplication
public class RulesServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RulesServiceApplication.class, args);
    }
}

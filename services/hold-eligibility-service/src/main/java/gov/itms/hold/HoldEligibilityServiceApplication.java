package gov.itms.hold;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** hold-eligibility-service: holds on traffic files, licences, vehicles and plates (CC-030..CC-034). */
@SpringBootApplication
public class HoldEligibilityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HoldEligibilityServiceApplication.class, args);
    }
}

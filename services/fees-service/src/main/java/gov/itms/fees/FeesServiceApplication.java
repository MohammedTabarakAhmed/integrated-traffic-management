package gov.itms.fees;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** fees-service: fee schedules with maker-checker and itemised, frozen quotes (CC-040..CC-047). */
@SpringBootApplication
public class FeesServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeesServiceApplication.class, args);
    }
}

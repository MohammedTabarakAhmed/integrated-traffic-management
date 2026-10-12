package gov.itms.examination;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"gov.itms.examination", "gov.itms.platform"})
public class ExaminationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExaminationServiceApplication.class, args);
    }
}

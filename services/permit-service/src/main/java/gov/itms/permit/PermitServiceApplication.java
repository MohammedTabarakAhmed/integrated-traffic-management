package gov.itms.permit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"gov.itms.permit", "gov.itms.platform"})
public class PermitServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PermitServiceApplication.class, args);
    }
}

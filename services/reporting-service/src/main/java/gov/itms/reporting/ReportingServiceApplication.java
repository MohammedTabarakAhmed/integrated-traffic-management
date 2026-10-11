package gov.itms.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Reporting and analytics (RPT-001, RPT-004, RPT-020, RPT-022): reports over a warehouse fed by domain events. */
@SpringBootApplication(scanBasePackages = {"gov.itms.reporting", "gov.itms.platform"})
public class ReportingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportingServiceApplication.class, args);
    }
}

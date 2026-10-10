package gov.itms.crossborder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"gov.itms.crossborder", "gov.itms.platform"})
public class CrossborderServiceApplication {
    public static void main(String[] args) { SpringApplication.run(CrossborderServiceApplication.class, args); }
}

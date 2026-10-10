package gov.itms.geo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages={"gov.itms.geo","gov.itms.platform"})
public class GeoServiceApplication {
    public static void main(String[] args) { SpringApplication.run(GeoServiceApplication.class,args); }
}

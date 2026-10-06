package gov.itms.trafficfile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** traffic-file-service: traffic files of individuals and companies (DL-001..DL-013). */
@SpringBootApplication
public class TrafficFileServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrafficFileServiceApplication.class, args);
    }
}

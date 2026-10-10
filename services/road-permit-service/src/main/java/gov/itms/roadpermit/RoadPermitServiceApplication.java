package gov.itms.roadpermit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages={"gov.itms.roadpermit","gov.itms.platform"})
public class RoadPermitServiceApplication { public static void main(String[] args){SpringApplication.run(RoadPermitServiceApplication.class,args);} }

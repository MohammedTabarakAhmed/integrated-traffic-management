package gov.itms.marine;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"gov.itms.marine","gov.itms.platform"})
public class MarineServiceApplication { public static void main(String[] args){SpringApplication.run(MarineServiceApplication.class,args);} }

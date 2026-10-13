package gov.itms.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** ai-service: AI and agentic automation platform with human-in-the-loop, kill switch, and governance (AI-001..AI-019). */
@SpringBootApplication(scanBasePackages={"gov.itms.ai","gov.itms.platform"})
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
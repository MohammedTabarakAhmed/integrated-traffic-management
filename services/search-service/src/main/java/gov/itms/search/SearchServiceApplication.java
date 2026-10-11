package gov.itms.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Unified staff search (CC-100, CC-102): one query, answered by the services that own the records. */
@SpringBootApplication(scanBasePackages = {"gov.itms.search", "gov.itms.platform"})
public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}

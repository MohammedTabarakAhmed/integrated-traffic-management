package gov.itms.search.infrastructure;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.search.application.Ports;
import gov.itms.search.application.SearchService;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Wires the configured sources ({@code itms.search.sources}) into the search. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfiguration {

    @Bean
    SearchService searchService(SearchProperties properties, AuditRecorder audit, ObjectMapper json) {
        List<Ports.Source> sources = properties.enabledSources().stream()
                .<Ports.Source>map(s -> new HttpSource(s, json)).toList();
        return new SearchService(sources, audit, properties.deadline(), properties.maxHits());
    }
}

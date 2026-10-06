package gov.itms.document.infrastructure;

import gov.itms.document.application.CertificateService;
import gov.itms.document.application.DocumentService;
import gov.itms.document.application.Ports;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires documents and certificates to PostgreSQL, the object store, clamd and the HTML renderer. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentServiceConfiguration {

    @Bean
    JdbcDocumentRepository documentRepository(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcDocumentRepository(jdbc, json);
    }

    @Bean
    Ports.ContentStore contentStore(DocumentProperties properties, Clock clock) {
        DocumentProperties.Store s = properties.store();
        return new S3ContentStore(s.endpoint(), s.bucket(), s.region(), s.accessKey(), s.secretKey(), s.timeout(),
                clock);
    }

    @Bean
    Ports.MalwareScanner malwareScanner(DocumentProperties properties) {
        DocumentProperties.Scanner s = properties.scanner();
        return new ClamdScanner(s.host(), s.port(), s.timeout());
    }

    @Bean
    DocumentService documentService(JdbcDocumentRepository repo, Ports.ContentStore store,
                                    Ports.MalwareScanner scanner, DocumentProperties properties, AuditRecorder audit,
                                    DomainEventRecorder events, Clock clock) {
        return new DocumentService(repo, store, scanner,
                properties.types().stream().map(DocumentProperties.Type::toPolicy).toList(), audit, events, clock);
    }

    @Bean
    CertificateService certificateService(JdbcDocumentRepository repo, Ports.ContentStore store,
                                          DocumentProperties properties, AuditRecorder audit,
                                          DomainEventRecorder events, Clock clock) {
        return new CertificateService(repo, store, new HtmlCertificateRenderer(), audit, events,
                new CertificateService.Settings(properties.certificates().numberPrefix(),
                        properties.certificates().verificationBaseUrl()), clock);
    }
}

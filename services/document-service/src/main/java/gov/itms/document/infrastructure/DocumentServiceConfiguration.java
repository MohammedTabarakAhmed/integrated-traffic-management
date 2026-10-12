package gov.itms.document.infrastructure;

import gov.itms.document.application.CertificateHandOff;
import gov.itms.document.application.CertificateService;
import gov.itms.document.application.DocumentService;
import gov.itms.document.application.Ports;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.client.ServiceClientProperties;
import gov.itms.platform.client.ServiceClients;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.events.ProcessedEvents;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires documents and certificates to PostgreSQL, the object store, clamd and the HTML renderer. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DocumentProperties.class, SealProperties.class})
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
                                          DocumentProperties properties, SealProperties seal, AuditRecorder audit,
                                          DomainEventRecorder events, Clock clock) {
        // Q-067, ADR-064 decision 6: production certificates are sealed PDFs; HTML is a development preview only.
        Ports.CertificateRenderer renderer = "HTML".equals(seal.format()) ? new HtmlCertificateRenderer()
                : new PdfCertificateRenderer(CertificateSeal.load(seal));
        return new CertificateService(repo, store, renderer, audit, events,
                new CertificateService.Settings(properties.certificates().numberPrefix(),
                        properties.certificates().verificationBaseUrl()), clock);
    }

    /**
     * Renders the certificates of other modules from their events (CC-082; Q-075 f, Q-078 a): off unless the broker
     * is available, like the event-driven notifications.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "itms.documents.consumer.enabled", havingValue = "true")
    static class CertificateConsumerConfiguration {

        @Bean
        CertificateHandOff certificateHandOff(CertificateService certificates, DocumentProperties properties,
                                              ProcessedEvents processed, Environment environment, Clock clock,
                                              ObjectProvider<ObservationRegistry> observations) {
            ServiceClientProperties client = Binder.get(environment)
                    .bind("itms.clients.traffic-files", ServiceClientProperties.class)
                    .orElseThrow(() -> new IllegalStateException("itms.clients.traffic-files is not configured"))
                    .validate("itms.clients.traffic-files");
            return new CertificateHandOff(certificates, new CertificateEvents.RemoteHolderNames(
                    ServiceClients.create(client, clock, observations.getIfAvailable(() -> ObservationRegistry.NOOP))),
                    processed, properties.certificates().events());
        }

        @Bean
        CertificateEvents.Listener certificateEventListener(CertificateHandOff handOff, ObjectMapper json) {
            return new CertificateEvents.Listener(handOff, json);
        }
    }
}

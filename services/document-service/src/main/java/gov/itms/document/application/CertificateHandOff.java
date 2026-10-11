package gov.itms.document.application;

import gov.itms.document.domain.Actor;
import gov.itms.document.domain.Certificate;
import gov.itms.document.domain.DomainException;
import gov.itms.platform.events.ProcessedEvents;
import gov.itms.platform.security.CurrentActor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders the certificates other modules issue (CC-082; Q-075 f, Q-078 a): the owning service keeps the certificate's
 * number and state and announces it in an event; document-service renders it from its bilingual template with a QR
 * code and stores it as a document, with the owning service's number as {@code reference}. The holder's name comes
 * from the traffic file. Which events are certificates is configuration ({@code itms.documents.certificate-events}).
 */
public class CertificateHandOff {

    private static final Logger LOG = LoggerFactory.getLogger(CertificateHandOff.class);
    static final String CONSUMER = "document-service-certificates";
    private static final Pattern FIELD = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]{0,39})}");

    /**
     * One certificate event: {@code templateCode} may name event fields in braces (for example
     * {@code VL_CERT_{kind}}); the other fields name the event fields of subject, holder's traffic file and the owning
     * service's certificate number.
     */
    public record CertificateEvent(String eventType, String templateCode, String subjectType, String subjectField,
                                   String holderFileField, String referenceField) {
        public CertificateEvent {
            if (eventType == null || templateCode == null || subjectType == null || subjectField == null
                    || holderFileField == null || referenceField == null) {
                throw new IllegalArgumentException("a certificate event needs eventType, templateCode, subjectType, "
                        + "subjectField, holderFileField and referenceField (CC-082)");
            }
        }
    }

    private final CertificateService certificates;
    private final Ports.HolderNames names;
    private final ProcessedEvents processed;
    private final List<CertificateEvent> mapping;

    public CertificateHandOff(CertificateService certificates, Ports.HolderNames names, ProcessedEvents processed,
                              List<CertificateEvent> mapping) {
        this.certificates = certificates;
        this.names = names;
        this.processed = processed;
        this.mapping = List.copyOf(mapping);
    }

    /**
     * Renders the certificate an event announces, once per event id. A partner that does not answer is thrown so the
     * event is redelivered; a certificate that cannot be rendered (no template, missing data, unknown holder) is
     * logged and skipped.
     *
     * @return the number of certificates rendered (0 or 1)
     */
    @Transactional
    public int onEvent(String eventId, String eventType, Map<String, String> values) {
        CertificateEvent x = mapping.stream().filter(m -> m.eventType().equals(eventType)).findFirst().orElse(null);
        if (x == null || !processed.firstDelivery(CONSUMER, eventId)) {
            return 0;
        }
        String template = template(x.templateCode(), values);
        String subject = values.get(x.subjectField());
        String reference = values.get(x.referenceField());
        String file = values.get(x.holderFileField());
        if (template == null || subject == null || reference == null || file == null) {
            LOG.warn("Certificate event {} of type {} lacks a field; not rendered", eventId, eventType);
            return 0;
        }
        Ports.HolderNames.Names holder = names.of(file).orElse(null);
        if (holder == null) {
            LOG.warn("Certificate event {}: holder file not found; not rendered", eventId);
            return 0;
        }
        CurrentActor system = CurrentActor.system("document-certificate-handoff");
        Map<String, String> data = new LinkedHashMap<>(values);
        data.remove("certificateNumber");
        data.put("sourceCertificateNumber", reference);
        try {
            Certificate.Issued c = certificates.issueAs(template, holder.nameNl(), holder.nameEn(), x.subjectType(),
                    subject, data, reference, new Actor(system.userId(), system.username()), system);
            LOG.info("Certificate {} rendered for {} {}", c.number(), x.subjectType(), reference);
            return 1;
        } catch (DomainException e) {
            LOG.warn("Certificate event {} of type {} not rendered ({})", eventId, eventType, e.code());
            return 0;
        }
    }

    private static String template(String pattern, Map<String, String> values) {
        Matcher m = FIELD.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            if (v == null || !v.matches("^[A-Z0-9_]{1,30}$")) {
                return null;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }
}

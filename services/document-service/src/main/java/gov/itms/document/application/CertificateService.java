package gov.itms.document.application;

import gov.itms.document.domain.Certificate;
import gov.itms.document.domain.Document;
import gov.itms.document.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Certificates and letters (CC-082, CC-083; ADR-051): bilingual templates ({@value #TEMPLATE_MANAGE}); issuing
 * ({@value #ISSUE}) fills the template, gives a unique number with a check digit and a random verification code,
 * renders the document with a QR code pointing to the public verification page, and stores it as a document;
 * revoking ({@value #REVOKE}); the public verification answers valid, expired or revoked for a number and its code.
 */
public class CertificateService {

    public static final String TEMPLATE_MANAGE = "certificate.template.manage";
    public static final String ISSUE = "certificate.issue";
    public static final String READ = "certificate.read";
    public static final String REVOKE = "certificate.revoke";
    static final String ENTITY = "Certificate";
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /** Number format and verification page (configuration). */
    public record Settings(String numberPrefix, String verificationBaseUrl) {
    }

    /** What the public page shows (CC-083): nothing beyond what is printed on the certificate. */
    public record Verification(String number, String titleNl, String titleEn, String holder,
                               Certificate.Validity validity, LocalDate issuedOn, LocalDate validUntil) {
    }

    private final Ports.DocumentRepository repo;
    private final Ports.ContentStore store;
    private final Ports.CertificateRenderer renderer;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Settings settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public CertificateService(Ports.DocumentRepository repo, Ports.ContentStore store,
                              Ports.CertificateRenderer renderer, AuditRecorder audit, DomainEventRecorder events,
                              Settings settings, Clock clock) {
        this.repo = repo;
        this.store = store;
        this.renderer = renderer;
        this.audit = audit;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public Certificate.Template publish(String code, String titleNl, String titleEn, String bodyNl, String bodyEn,
                                       Integer validityDays, String reason, Caller caller) {
        DocumentService.channelOf(caller);
        DocumentService.require(caller.grants().holds(TEMPLATE_MANAGE));
        String why = reason(reason);
        repo.lockTemplate(code);
        Certificate.Template t = new Certificate.Template(code, repo.nextTemplateVersion(code), titleNl, titleEn,
                bodyNl, bodyEn, validityDays, caller.actor(), clock.instant());
        repo.insertTemplate(t);
        audit.record("CERTIFICATE_TEMPLATE_PUBLISHED", "CertificateTemplate", code + ":" + t.versionNo(), null,
                Map.of("versionNo", t.versionNo()), why);
        return t;
    }

    @Transactional(readOnly = true)
    public List<Certificate.Template> templates(Caller caller) {
        DocumentService.require(caller.grants().holds(TEMPLATE_MANAGE) || caller.grants().holds(ISSUE));
        return repo.latestTemplates();
    }

    @Transactional
    public Certificate.Issued issue(String templateCode, String holderNameNl, String holderNameEn,
                                    String subjectType, String subjectId, Map<String, String> data, String reference,
                                    Caller caller) {
        DocumentService.channelOf(caller);
        DocumentService.require(caller.grants().holds(ISSUE));
        return issueAs(templateCode, holderNameNl, holderNameEn, subjectType, subjectId, data, reference,
                caller.actor(), null);
    }

    /**
     * Issues for {@code actor}; {@code system} is set when no user acts (a certificate requested by another module's
     * event, {@link CertificateHandOff}) and is then the audit and event actor.
     */
    Certificate.Issued issueAs(String templateCode, String holderNameNl, String holderNameEn, String subjectType,
                               String subjectId, Map<String, String> data, String reference,
                               gov.itms.document.domain.Actor actor, CurrentActor system) {
        Certificate.Template t = repo.latestTemplate(templateCode).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "CERTIFICATE_TEMPLATE_NOT_FOUND",
                "certificate template " + templateCode + " not found", List.of("CC-082"), templateCode));
        Document.Spec subject = new Document.Spec("CERTIFICATE", subjectType, subjectId, t.titleEn(),
                Document.Classification.INTERNAL, null);
        name("holderNameNl", holderNameNl);
        name("holderNameEn", holderNameEn);
        Map<String, String> values = new LinkedHashMap<>(data == null ? Map.of() : data);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        String number = number(repo.nextCertificateSequence(), today.getYear());
        String code = verificationCode();
        values.putIfAbsent("certificateNumber", number);
        values.putIfAbsent("holderNameNl", holderNameNl);
        values.putIfAbsent("holderNameEn", holderNameEn);
        values.putIfAbsent("issueDate", today.toString());
        String bodyNl = t.fill(t.bodyNl(), values);
        String bodyEn = t.fill(t.bodyEn(), values);
        LocalDate validUntil = t.validityDays() == null ? null : today.plusDays(t.validityDays() - 1L);
        UUID documentId = UUID.randomUUID();
        Certificate.Issued issued = new Certificate.Issued(number, code, t.code(), t.versionNo(), holderNameNl,
                holderNameEn, subjectType, subjectId, values, reference, today, validUntil,
                Certificate.Status.ISSUED, null, null, documentId, actor, now);
        Ports.CertificateRenderer.Rendered rendered = renderer.render(t, issued, bodyNl, bodyEn,
                verificationUrl(number, code));
        String sha = sha256(rendered.content());
        String key = "certificates/" + number;
        store.put(key, rendered.content(), rendered.mediaType(), sha);
        Document doc = Document.create(documentId, subject, actor, now);
        repo.insert(doc);
        doc.available(rendered.mediaType(), rendered.content().length, sha, key, "GENERATED", now);
        repo.saveContent(doc);
        repo.insertCertificate(issued);
        Map<String, Object> snapshot = snapshot(issued);
        if (system == null) {
            audit.record("CERTIFICATE_ISSUED", ENTITY, number, null, snapshot, null);
            events.record("itms.document.certificate-issued", number, snapshot);
        } else {
            audit.recordAs(system, "CERTIFICATE_ISSUED", ENTITY, number, null, snapshot, null);
            events.recordAs(system, "itms.document.certificate-issued", number, snapshot);
        }
        return issued;
    }

    @Transactional
    public Certificate.Issued get(String number, Caller caller) {
        DocumentService.require(caller.grants().holds(READ) || caller.grants().holds(ISSUE));
        Certificate.Issued c = repo.findCertificate(number).orElseThrow(() -> notFound(number));
        audit.record("CERTIFICATE_VIEWED", ENTITY, number, null, null, null);
        return c;
    }

    @Transactional
    public Certificate.Issued revoke(String number, String reason, Caller caller) {
        DocumentService.channelOf(caller);
        DocumentService.require(caller.grants().holds(REVOKE));
        String why = reason(reason);
        Certificate.Issued c = repo.findCertificateForUpdate(number).orElseThrow(() -> notFound(number));
        Certificate.Issued revoked = c.revoke(why, clock.instant());
        repo.saveRevocation(revoked);
        audit.record("CERTIFICATE_REVOKED", ENTITY, number, Map.of("status", c.status().name()),
                Map.of("status", revoked.status().name()), why);
        events.record("itms.document.certificate-revoked", number, snapshot(revoked));
        return revoked;
    }

    /**
     * The public check (CC-083). An unknown number and a wrong code give the same 404, so the page reveals nothing
     * about numbers it is not shown the code of. Every check is audited without the caller's identity.
     */
    @Transactional
    public Verification verify(String number, String code) {
        Certificate.Issued c = repo.findCertificate(number)
                .filter(x -> code != null && MessageDigest.isEqual(
                        x.verificationCode().getBytes(StandardCharsets.US_ASCII),
                        code.toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII)))
                .orElseThrow(() -> notFound(number));
        Certificate.Template t = repo.latestTemplate(c.templateCode()).orElseThrow();
        Certificate.Validity validity = c.validity(LocalDate.ofInstant(clock.instant(), clock.getZone()));
        audit.recordAs(CurrentActor.system("public-verification"), "CERTIFICATE_VERIFIED", ENTITY, number, null,
                Map.of("validity", validity.name()), null);
        return new Verification(c.number(), t.titleNl(), t.titleEn(), c.maskedHolder(), validity, c.issuedOn(),
                c.validUntil());
    }

    // ---- helpers

    String number(long sequence, int year) {
        String digits = year + String.format("%08d", sequence);
        return settings.numberPrefix() + "-" + year + "-" + String.format("%08d", sequence) + "-" + luhn(digits);
    }

    static int luhn(String digits) {
        int sum = 0;
        boolean twice = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            twice = !twice;
        }
        return (10 - sum % 10) % 10;
    }

    private String verificationCode() {
        char[] code = new char[10];
        for (int i = 0; i < code.length; i++) {
            code[i] = CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)];
        }
        return new String(code);
    }

    private String verificationUrl(String number, String code) {
        return settings.verificationBaseUrl() + "/" + number + "?code=" + code;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void name(String field, String value) {
        if (value == null || value.isBlank() || value.length() > 200
                || value.chars().anyMatch(Character::isISOControl)) {
            throw DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("CC-082"),
                    field);
        }
    }

    static String reason(String value) {
        String v = value == null ? "" : value.strip();
        if (v.length() < 3 || v.length() > 500 || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("CC-082"));
        }
        return v;
    }

    private static Map<String, Object> snapshot(Certificate.Issued c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", c.number());
        m.put("templateCode", c.templateCode());
        m.put("templateVersion", c.templateVersion());
        m.put("subjectType", c.subjectType());
        m.put("subjectId", c.subjectId());
        m.put("reference", c.reference());
        m.put("issuedOn", c.issuedOn().toString());
        m.put("validUntil", c.validUntil() == null ? null : c.validUntil().toString());
        m.put("status", c.status().name());
        m.put("documentId", c.documentId().toString());
        return m;
    }

    private static DomainException notFound(String number) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "CERTIFICATE_NOT_FOUND",
                "certificate not found", List.of("CC-083"));
    }
}

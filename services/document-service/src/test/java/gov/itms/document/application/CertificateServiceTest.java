package gov.itms.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.document.domain.Actor;
import gov.itms.document.domain.Certificate;
import gov.itms.document.domain.CertificateTest;
import gov.itms.document.domain.Channel;
import gov.itms.document.domain.Document;
import gov.itms.document.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.events.ProcessedEvents;
import gov.itms.platform.security.CurrentActor;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-082")
class CertificateServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    private final Repo repo = new Repo();
    private final Map<String, byte[]> stored = new HashMap<>();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private String renderedUrl;
    private final CertificateService service = new CertificateService(repo, new Ports.ContentStore() {
        @Override
        public void put(String key, Path file, String mediaType, String sha256Hex) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void put(String key, byte[] content, String mediaType, String sha256Hex) {
            stored.put(key, content);
        }

        @Override
        public InputStream get(String key) {
            throw new UnsupportedOperationException();
        }
    }, (template, certificate, bodyNl, bodyEn, url) -> {
        renderedUrl = url;
        return new Ports.CertificateRenderer.Rendered((bodyNl + "|" + bodyEn).getBytes(StandardCharsets.UTF_8),
                "text/html");
    }, new AuditRecorder() {
        @Override
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            audit.add(action + " " + entityId);
        }

        @Override
        public void recordAs(CurrentActor actor, String action, String entityType, String entityId,
                             Object oldValue, Object newValue, String reason) {
            record(action, entityType, entityId, oldValue, newValue, reason);
        }
    }, new DomainEventRecorder() {
        @Override
        public void record(String eventType, String subject, Object data) {
            events.add(eventType + " " + subject);
        }

        @Override
        public void recordAs(CurrentActor actor, String eventType, String subject, Object data) {
            record(eventType, subject, data);
        }
    }, new CertificateService.Settings("CRT", "https://verify.itms.example/v1/certificate-verifications"),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static Caller caller(String id, String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor(id, id), Channel.BACKOFFICE, new Caller.Grants() {
            @Override
            public boolean allowsInRegion(String permission, String region) {
                return held.contains(permission);
            }

            @Override
            public boolean holds(String permission) {
                return held.contains(permission);
            }
        });
    }

    private static final Caller ADMIN = caller("u-admin", CertificateService.TEMPLATE_MANAGE,
            CertificateService.REVOKE);
    private static final Caller ISSUER = caller("u-issuer", CertificateService.ISSUE);

    @Test
    @DisplayName("CC-082: the check digit is the Luhn digit (known answers); numbers are prefix-year-sequence-check")
    void luhnAndNumber() {
        assertThat(CertificateService.luhn("7992739871")).isEqualTo(3);
        assertThat(CertificateService.luhn("0")).isZero();
        assertThat(CertificateService.luhn("202600000001")).isEqualTo(luhnByHand("202600000001"));
        String number = service.number(1, 2026);
        assertThat(number).matches("CRT-2026-00000001-\\d");
        String digits = number.replace("CRT-", "").replace("-", "");
        assertThat(validLuhn(digits)).as("the full number passes the Luhn check").isTrue();
        assertThat(validLuhn(digits.substring(0, digits.length() - 1) + ((digits.charAt(digits.length() - 1) - '0'
                + 1) % 10))).isFalse();
    }

    private static int luhnByHand(String digits) {
        for (int check = 0; check < 10; check++) {
            if (validLuhn(digits + check)) {
                return check;
            }
        }
        throw new AssertionError();
    }

    private static boolean validLuhn(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                d = d * 2 > 9 ? d * 2 - 9 : d * 2;
            }
            sum += d;
        }
        return sum % 10 == 0;
    }

    @Test
    @Tag("CC-082")
    @DisplayName("CC-082, Q-075 f, Q-078 a: another module's certificate event is rendered once, with the holder's name"
            + " from the traffic file and the module's number as reference")
    void handOff() {
        service.publish("VL_CERT_CLEARANCE", "براءة ذمة", "Clearance", "{{holderNameNl}} {{vin}}",
                "{{holderNameEn}} {{vin}} {{sourceCertificateNumber}}", null, "first version", ADMIN);
        Set<String> seen = new java.util.HashSet<>();
        CertificateHandOff handOff = new CertificateHandOff(service,
                file -> "TF-1".equals(file) ? Optional.of(new Ports.HolderNames.Names("محمد أحمد", "Mohammed Ahmed"))
                        : Optional.empty(),
                new ProcessedEvents(null, null) {
                    @Override
                    public boolean firstDelivery(String consumer, String eventId) {
                        return seen.add(consumer + "/" + eventId);
                    }
                },
                List.of(new CertificateHandOff.CertificateEvent("itms.vehicle.certificate-issued", "VL_CERT_{kind}",
                        "VEHICLE", "vin", "ownerFileNumber", "number")));
        Map<String, String> event = Map.of("number", "VC-2026-1", "kind", "CLEARANCE", "vin", "VIN-9",
                "ownerFileNumber", "TF-1");
        assertThat(handOff.onEvent("e-1", "itms.vehicle.certificate-issued", event)).isEqualTo(1);
        assertThat(handOff.onEvent("e-1", "itms.vehicle.certificate-issued", event)).as("redelivery").isZero();
        assertThat(handOff.onEvent("e-2", "itms.vehicle.registered", event)).as("not a certificate").isZero();
        Certificate.Issued c = repo.certificates.values().iterator().next();
        assertThat(c.reference()).isEqualTo("VC-2026-1");
        assertThat(c.subjectId()).isEqualTo("VIN-9");
        assertThat(new String(stored.get("certificates/" + c.number()), StandardCharsets.UTF_8))
                .isEqualTo("محمد أحمد VIN-9|Mohammed Ahmed VIN-9 VC-2026-1");
        assertThat(audit).contains("CERTIFICATE_ISSUED " + c.number());

        Map<String, String> unknownKind = new HashMap<>(event);
        unknownKind.put("kind", "EXPORT");
        assertThat(handOff.onEvent("e-3", "itms.vehicle.certificate-issued", unknownKind))
                .as("no template: skipped").isZero();
        Map<String, String> unknownHolder = new HashMap<>(event);
        unknownHolder.put("ownerFileNumber", "TF-404");
        assertThat(handOff.onEvent("e-4", "itms.vehicle.certificate-issued", unknownHolder)).isZero();
        assertThat(handOff.onEvent("e-5", "itms.vehicle.certificate-issued", Map.of("kind", "CLEARANCE")))
                .as("fields missing").isZero();
        assertThat(repo.certificates).hasSize(1);
    }

    @Test
    @Tag("CC-083")
    @DisplayName("CC-082, CC-083: issuing fills, stores the rendered document and points the QR at number + code")
    void issueAndVerify() {
        Certificate.Template t = CertificateTest.template(30);
        service.publish(t.code(), t.titleNl(), t.titleEn(), t.bodyNl(), t.bodyEn(), 30, "first version", ADMIN);
        Certificate.Issued c = service.issue("NO_OBJECTION", "محمد أحمد", "Mohammed Ahmed", "VEHICLE", "VIN-1",
                Map.of("plate", "A 1"), "REF-1", ISSUER);
        assertThat(c.number()).startsWith("CRT-2026-00000001-");
        assertThat(c.verificationCode()).matches("[A-HJ-NP-Z2-9]{10}");
        assertThat(c.validUntil()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(renderedUrl).isEqualTo("https://verify.itms.example/v1/certificate-verifications/" + c.number()
                + "?code=" + c.verificationCode());
        assertThat(new String(stored.get("certificates/" + c.number()), StandardCharsets.UTF_8))
                .isEqualTo("نشهد أن محمد أحمد|We certify that Mohammed Ahmed owns A 1");
        Document doc = repo.documents.get(c.documentId());
        assertThat(doc.status()).isEqualTo(Document.Status.AVAILABLE);
        assertThat(doc.spec().classification()).isEqualTo(Document.Classification.INTERNAL);
        assertThat(events).contains("itms.document.certificate-issued " + c.number());

        CertificateService.Verification v = service.verify(c.number(), c.verificationCode().toLowerCase());
        assertThat(v.validity()).isEqualTo(Certificate.Validity.VALID);
        assertThat(v.holder()).isEqualTo("M. A.");
        assertThatThrownBy(() -> service.verify(c.number(), "WRONGCODE2")).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CERTIFICATE_NOT_FOUND");
        assertThatThrownBy(() -> service.verify("CRT-2026-99999999-0", c.verificationCode()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("CERTIFICATE_NOT_FOUND");
        assertThatThrownBy(() -> service.verify(c.number(), null)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CERTIFICATE_NOT_FOUND");

        service.revoke(c.number(), "issued in error", ADMIN);
        assertThat(service.verify(c.number(), c.verificationCode()).validity())
                .isEqualTo(Certificate.Validity.REVOKED);
        assertThat(audit).contains("CERTIFICATE_ISSUED " + c.number(), "CERTIFICATE_VERIFIED " + c.number(),
                "CERTIFICATE_REVOKED " + c.number());
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: issuing, revoking and publishing need their permissions; missing data is refused")
    void permissions() {
        Certificate.Template t = CertificateTest.template(null);
        assertThatThrownBy(() -> service.publish(t.code(), t.titleNl(), t.titleEn(), t.bodyNl(), t.bodyEn(), null,
                "first version", ISSUER)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SCOPE_EXCEEDED");
        service.publish(t.code(), t.titleNl(), t.titleEn(), t.bodyNl(), t.bodyEn(), null, "first version", ADMIN);
        assertThatThrownBy(() -> service.issue("NO_OBJECTION", "م", "M", "VEHICLE", "VIN-1", Map.of(), null,
                ISSUER)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("CERTIFICATE_DATA_MISSING");
        assertThatThrownBy(() -> service.issue("NONE", "م", "M", "VEHICLE", "VIN-1", Map.of(), null, ISSUER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("CERTIFICATE_TEMPLATE_NOT_FOUND");
        Certificate.Issued c = service.issue("NO_OBJECTION", "م", "M", "VEHICLE", "VIN-1", Map.of("plate", "A"),
                null, ISSUER);
        assertThat(c.validUntil()).isNull();
        assertThatThrownBy(() -> service.revoke(c.number(), "not allowed", ISSUER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
    }

    private static final class Repo implements Ports.DocumentRepository {
        final Map<UUID, Document> documents = new HashMap<>();
        final Map<String, List<Certificate.Template>> templates = new HashMap<>();
        final Map<String, Certificate.Issued> certificates = new HashMap<>();
        long sequence;

        @Override
        public void insert(Document document) {
            documents.put(document.id(), document);
        }

        @Override
        public Optional<Document> find(UUID id) {
            return Optional.ofNullable(documents.get(id));
        }

        @Override
        public Optional<Document> findForUpdate(UUID id) {
            return find(id);
        }

        @Override
        public void saveContent(Document document) {
        }

        @Override
        public void insertLink(UUID documentId, Document.Link link) {
        }

        @Override
        public List<Document.Link> links(UUID documentId) {
            return List.of();
        }

        @Override
        public List<Document> byOwner(String ownerType, String ownerId, int limit) {
            return List.of();
        }

        @Override
        public List<Document> byRecord(String recordType, String recordId, int limit) {
            return List.of();
        }

        @Override
        public void lockTemplate(String code) {
        }

        @Override
        public int nextTemplateVersion(String code) {
            return templates.getOrDefault(code, List.of()).size() + 1;
        }

        @Override
        public void insertTemplate(Certificate.Template template) {
            templates.computeIfAbsent(template.code(), c -> new ArrayList<>()).add(template);
        }

        @Override
        public Optional<Certificate.Template> latestTemplate(String code) {
            List<Certificate.Template> all = templates.getOrDefault(code, List.of());
            return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
        }

        @Override
        public List<Certificate.Template> latestTemplates() {
            return templates.values().stream().map(List::getLast).toList();
        }

        @Override
        public long nextCertificateSequence() {
            return ++sequence;
        }

        @Override
        public void insertCertificate(Certificate.Issued certificate) {
            certificates.put(certificate.number(), certificate);
        }

        @Override
        public Optional<Certificate.Issued> findCertificate(String number) {
            return Optional.ofNullable(certificates.get(number));
        }

        @Override
        public Optional<Certificate.Issued> findCertificateForUpdate(String number) {
            return findCertificate(number);
        }

        @Override
        public void saveRevocation(Certificate.Issued certificate) {
            certificates.put(certificate.number(), certificate);
        }
    }
}

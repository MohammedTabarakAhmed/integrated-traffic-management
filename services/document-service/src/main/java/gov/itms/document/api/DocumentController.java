package gov.itms.document.api;

import gov.itms.document.application.CertificateService;
import gov.itms.document.application.DocumentService;
import gov.itms.document.domain.Certificate;
import gov.itms.document.domain.Document;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Documents, links, certificates and the public verification of {@code document-service.v1.yaml}. */
@RestController
class DocumentController {

    private final DocumentService documents;
    private final CertificateService certificates;
    private final Callers callers;

    DocumentController(DocumentService documents, CertificateService certificates, Callers callers) {
        this.documents = documents;
        this.certificates = certificates;
        this.callers = callers;
    }

    record CreateRequest(@NotBlank String type, @NotBlank String ownerType, @NotBlank String ownerId,
                         @Size(max = 200) String title, Document.Classification classification,
                         LocalDate expiryDate) {
    }

    record DocumentDto(UUID id, String type, String ownerType, String ownerId, String title,
                       Document.Classification classification, LocalDate expiryDate, Document.Status status,
                       String mediaType, Long size, String sha256, String createdBy, Instant createdAt,
                       Instant availableAt) {
        static DocumentDto of(Document d) {
            Document.Spec s = d.spec();
            return new DocumentDto(d.id(), s.type(), s.ownerType(), s.ownerId(), s.title(), s.classification(),
                    s.expiryDate(), d.status(), d.mediaType(), d.mediaType() == null ? null : d.size(), d.sha256(),
                    d.createdBy().id(), d.createdAt(), d.availableAt());
        }
    }

    record LinkRequest(@NotBlank String recordType, @NotBlank String recordId) {
    }

    record LinkDto(String recordType, String recordId, String linkedBy, Instant linkedAt) {
        static LinkDto of(Document.Link l) {
            return new LinkDto(l.recordType(), l.recordId(), l.linkedBy().id(), l.linkedAt());
        }
    }

    record TypeDto(String code, List<String> mediaTypes, long maxBytes, Document.Classification classification) {
    }

    record TemplateRequest(@NotBlank String code, @NotBlank @Size(max = 200) String titleNl,
                           @NotBlank @Size(max = 200) String titleEn, @NotBlank @Size(max = 20000) String bodyNl,
                           @NotBlank @Size(max = 20000) String bodyEn, Integer validityDays,
                           @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record TemplateDto(String code, int versionNo, String titleNl, String titleEn, String bodyNl, String bodyEn,
                       Integer validityDays, String publishedBy, Instant publishedAt) {
        static TemplateDto of(Certificate.Template t) {
            return new TemplateDto(t.code(), t.versionNo(), t.titleNl(), t.titleEn(), t.bodyNl(), t.bodyEn(),
                    t.validityDays(), t.publishedBy().id(), t.publishedAt());
        }
    }

    record IssueRequest(@NotBlank String templateCode, @NotBlank @Size(max = 200) String holderNameNl,
                        @NotBlank @Size(max = 200) String holderNameEn, @NotBlank String subjectType,
                        @NotBlank String subjectId, @Size(max = 50) Map<String, @Size(max = 500) String> data,
                        @Size(max = 60) String reference) {
    }

    /** Staff view of a certificate; the verification code is part of it (it is printed on the certificate). */
    record CertificateDto(String number, String verificationCode, String templateCode, int templateVersion,
                          String holderNameNl, String holderNameEn, String subjectType, String subjectId,
                          String reference, LocalDate issuedOn, LocalDate validUntil, Certificate.Status status,
                          Instant revokedAt, String revocationReason, UUID documentId, String issuedBy,
                          Instant issuedAt) {
        static CertificateDto of(Certificate.Issued c) {
            return new CertificateDto(c.number(), c.verificationCode(), c.templateCode(), c.templateVersion(),
                    c.holderNameNl(), c.holderNameEn(), c.subjectType(), c.subjectId(), c.reference(), c.issuedOn(),
                    c.validUntil(), c.status(), c.revokedAt(), c.revocationReason(), c.documentId(),
                    c.issuedBy().id(), c.issuedAt());
        }
    }

    record RevokeRequest(@NotNull @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record Collection<T>(List<T> items) {
    }

    // ---- documents (CC-080, CC-081, CC-085)

    @GetMapping("/v1/document-types")
    @PreAuthorize("isAuthenticated()")
    Collection<TypeDto> types() {
        return new Collection<>(documents.types().stream().map(t -> new TypeDto(t.code(),
                t.mediaTypes().stream().sorted().toList(), t.maxBytes(), t.classification())).toList());
    }

    @PostMapping("/v1/documents")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<DocumentDto> create(@Valid @RequestBody CreateRequest body) {
        Document d = documents.create(new Document.Spec(body.type(), body.ownerType(), body.ownerId(), body.title(),
                body.classification(), body.expiryDate()), callers.current());
        return ResponseEntity.created(URI.create("/v1/documents/" + d.id())).body(DocumentDto.of(d));
    }

    @PostMapping(path = "/v1/documents/{id}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    DocumentDto upload(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        return DocumentDto.of(documents.upload(id, request.getInputStream(), callers.current()));
    }

    @GetMapping("/v1/documents/{id}")
    @PreAuthorize("isAuthenticated()")
    DocumentDto get(@PathVariable UUID id) {
        return DocumentDto.of(documents.get(id, callers.current()));
    }

    @GetMapping("/v1/documents/{id}/content")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<InputStreamResource> content(@PathVariable UUID id) {
        DocumentService.Content c = documents.content(id, callers.current());
        Document d = c.document();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.mediaType()))
                .contentLength(d.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.id() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("Repr-Digest", "sha-256=:" + java.util.Base64.getEncoder().encodeToString(
                        HexFormat.of().parseHex(d.sha256())) + ":")
                .body(new InputStreamResource(c.stream()));
    }

    @GetMapping("/v1/documents")
    @PreAuthorize("isAuthenticated()")
    Collection<DocumentDto> list(@RequestParam(required = false) String ownerType,
                                 @RequestParam(required = false) String ownerId,
                                 @RequestParam(required = false) String recordType,
                                 @RequestParam(required = false) String recordId,
                                 @RequestParam(defaultValue = "50") int limit) {
        List<Document> found;
        if (ownerType != null && ownerId != null) {
            found = documents.byOwner(ownerType, ownerId, limit, callers.current());
        } else if (recordType != null && recordId != null) {
            found = documents.byRecord(recordType, recordId, limit, callers.current());
        } else {
            throw gov.itms.document.domain.DomainException.validation("FIELD_INVALID",
                    "give ownerType and ownerId, or recordType and recordId", List.of("CC-080"),
                    "ownerType/recordType");
        }
        return new Collection<>(found.stream().map(DocumentDto::of).toList());
    }

    @PostMapping("/v1/documents/{id}/links")
    @PreAuthorize("hasAuthority('document.create')")
    ResponseEntity<LinkDto> link(@PathVariable UUID id, @Valid @RequestBody LinkRequest body) {
        Document.Link l = documents.link(id, body.recordType(), body.recordId(), callers.current());
        return ResponseEntity.created(URI.create("/v1/documents/" + id + "/links")).body(LinkDto.of(l));
    }

    @GetMapping("/v1/documents/{id}/links")
    @PreAuthorize("isAuthenticated()")
    Collection<LinkDto> links(@PathVariable UUID id) {
        return new Collection<>(documents.links(id, callers.current()).stream().map(LinkDto::of).toList());
    }

    // ---- certificates (CC-082, CC-083)

    @PostMapping("/v1/certificate-templates")
    @PreAuthorize("hasAuthority('certificate.template.manage')")
    ResponseEntity<TemplateDto> publish(@Valid @RequestBody TemplateRequest body) {
        Certificate.Template t = certificates.publish(body.code(), body.titleNl(), body.titleEn(), body.bodyNl(),
                body.bodyEn(), body.validityDays(), body.reason(), callers.current());
        return ResponseEntity.created(URI.create("/v1/certificate-templates")).body(TemplateDto.of(t));
    }

    @GetMapping("/v1/certificate-templates")
    @PreAuthorize("hasAuthority('certificate.template.manage') or hasAuthority('certificate.issue')")
    Collection<TemplateDto> templates() {
        return new Collection<>(certificates.templates(callers.current()).stream().map(TemplateDto::of).toList());
    }

    @PostMapping("/v1/certificates")
    @PreAuthorize("hasAuthority('certificate.issue')")
    ResponseEntity<CertificateDto> issue(@Valid @RequestBody IssueRequest body) {
        Certificate.Issued c = certificates.issue(body.templateCode(), body.holderNameNl(), body.holderNameEn(),
                body.subjectType(), body.subjectId(), body.data(), body.reference(), callers.current());
        return ResponseEntity.created(URI.create("/v1/certificates/" + c.number())).body(CertificateDto.of(c));
    }

    @GetMapping("/v1/certificates/{number}")
    @PreAuthorize("hasAuthority('certificate.read') or hasAuthority('certificate.issue')")
    CertificateDto certificate(@PathVariable String number) {
        return CertificateDto.of(certificates.get(number, callers.current()));
    }

    @PostMapping("/v1/certificates/{number}/revocations")
    @PreAuthorize("hasAuthority('certificate.revoke')")
    CertificateDto revoke(@PathVariable String number, @Valid @RequestBody RevokeRequest body) {
        return CertificateDto.of(certificates.revoke(number, body.reason(), callers.current()));
    }

    /** Public (CC-083): no token; the number and the code printed in the QR code. */
    @GetMapping("/v1/certificate-verifications/{number}")
    CertificateService.Verification verify(@PathVariable String number, @RequestParam String code) {
        return certificates.verify(number, code);
    }
}

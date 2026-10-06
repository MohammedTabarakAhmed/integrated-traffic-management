package gov.itms.document.infrastructure;

import gov.itms.document.application.DocumentService;
import gov.itms.document.domain.Document;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Document settings, {@code itms.documents.*} (NFR-042). The access and secret keys of the object store arrive as
 * secret files (SEC-004, ADR-038), never as plain settings.
 *
 * @param store        object store (S3 API, ADR-005)
 * @param scanner      clamd address (CC-081)
 * @param types        allowed file types and maximum size per document type (CC-081), at least one
 * @param certificates certificate number prefix and the public verification page (CC-083)
 */
@ConfigurationProperties("itms.documents")
public record DocumentProperties(Store store, Scanner scanner, List<Type> types, @DefaultValue Certificates certificates) {

    private static final Set<String> KNOWN_MEDIA = Set.of("application/pdf", "image/jpeg", "image/png", "image/webp",
            "image/tiff", "image/heic", "video/mp4", "video/quicktime");

    public record Store(URI endpoint, String bucket, @DefaultValue("local") String region, String accessKey,
                        String secretKey, @DefaultValue("10s") Duration timeout) {
        public Store {
            if (endpoint == null || bucket == null || !bucket.matches("^[a-z0-9][a-z0-9.-]{2,62}$")) {
                throw new IllegalArgumentException("itms.documents.store.endpoint and bucket are required (ADR-005)");
            }
            if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
                throw new IllegalArgumentException("itms.documents.store.access-key and secret-key must be provided "
                        + "as secret files (SEC-004)");
            }
            if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("itms.documents.store.timeout must be 1 s to 10 min (NFR-042)");
            }
        }
    }

    public record Scanner(String host, @DefaultValue("3310") int port, @DefaultValue("60s") Duration timeout) {
        public Scanner {
            if (host == null || host.isBlank() || port < 1 || port > 65535) {
                throw new IllegalArgumentException("itms.documents.scanner.host and port are required (CC-081)");
            }
            if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("itms.documents.scanner.timeout must be 1 s to 10 min (NFR-042)");
            }
        }
    }

    /** One document type's upload rules; max size 1 KB to 5 GB. */
    public record Type(String code, List<String> mediaTypes, DataSize maxSize,
                       @DefaultValue("CONFIDENTIAL") Document.Classification classification) {
        public Type {
            if (code == null || !code.matches(Document.CODE)) {
                throw new IllegalArgumentException("itms.documents.types[].code must be a code (CC-081)");
            }
            if (mediaTypes == null || mediaTypes.isEmpty() || !KNOWN_MEDIA.containsAll(mediaTypes)) {
                throw new IllegalArgumentException("itms.documents.types[" + code + "].media-types must be among "
                        + KNOWN_MEDIA + " (CC-081)");
            }
            if (maxSize == null || maxSize.toBytes() < 1024 || maxSize.toBytes() > DataSize.ofGigabytes(5).toBytes()) {
                throw new IllegalArgumentException("itms.documents.types[" + code + "].max-size must be 1KB-5GB "
                        + "(CC-081, NFR-042)");
            }
        }

        DocumentService.TypePolicy toPolicy() {
            return new DocumentService.TypePolicy(code, Set.copyOf(mediaTypes), maxSize.toBytes(), classification);
        }
    }

    public record Certificates(@DefaultValue("CRT") String numberPrefix,
                               @DefaultValue("https://verify.itms.example/v1/certificate-verifications")
                               String verificationBaseUrl) {
        public Certificates {
            if (!numberPrefix.matches("^[A-Z]{2,6}$")) {
                throw new IllegalArgumentException("itms.documents.certificates.number-prefix is 2-6 letters");
            }
            if (!verificationBaseUrl.startsWith("https://") && !verificationBaseUrl.startsWith("http://localhost")) {
                throw new IllegalArgumentException("itms.documents.certificates.verification-base-url must be https");
            }
        }
    }

    public DocumentProperties {
        if (store == null || scanner == null) {
            throw new IllegalArgumentException("itms.documents.store and scanner are required");
        }
        types = types == null ? List.of() : List.copyOf(types);
        if (types.isEmpty()) {
            throw new IllegalArgumentException("itms.documents.types needs at least one document type (CC-081)");
        }
        Set<String> codes = new HashSet<>();
        for (Type t : types) {
            if (!codes.add(t.code()) || "CERTIFICATE".equals(t.code())) {
                throw new IllegalArgumentException("itms.documents.types lists " + t.code()
                        + " twice or uses the reserved CERTIFICATE type");
            }
        }
    }
}

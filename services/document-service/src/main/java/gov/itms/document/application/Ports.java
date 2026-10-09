package gov.itms.document.application;

import gov.itms.document.domain.Certificate;
import gov.itms.document.domain.Document;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ports of document-service: metadata repository, object store, malware scanner and certificate renderer. */
public final class Ports {

    private Ports() {
    }

    /** Document and certificate metadata (PostgreSQL). */
    public interface DocumentRepository {

        void insert(Document document);

        Optional<Document> find(UUID id);

        Optional<Document> findForUpdate(UUID id);

        void saveContent(Document document);

        void insertLink(UUID documentId, Document.Link link);

        List<Document.Link> links(UUID documentId);

        List<Document> byOwner(String ownerType, String ownerId, int limit);

        List<Document> byRecord(String recordType, String recordId, int limit);

        void lockTemplate(String code);

        int nextTemplateVersion(String code);

        void insertTemplate(Certificate.Template template);

        Optional<Certificate.Template> latestTemplate(String code);

        List<Certificate.Template> latestTemplates();

        long nextCertificateSequence();

        void insertCertificate(Certificate.Issued certificate);

        Optional<Certificate.Issued> findCertificate(String number);

        Optional<Certificate.Issued> findCertificateForUpdate(String number);

        void saveRevocation(Certificate.Issued certificate);
    }

    /** The S3-compatible object store (ADR-005): Ceph RGW in production, MinIO locally. */
    public interface ContentStore {

        void put(String key, Path file, String mediaType, String sha256Hex);

        void put(String key, byte[] content, String mediaType, String sha256Hex);

        InputStream get(String key);
    }

    /** Malware scanning (CC-081, SEC-022). */
    public interface MalwareScanner {

        /** Scan result: clean, or the name of what was found. */
        record Result(boolean clean, String finding) {
        }

        /** Throws {@link ScannerUnavailableException} when no verdict could be obtained. */
        Result scan(Path file);
    }

    public static class ScannerUnavailableException extends RuntimeException {
        public ScannerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class StoreUnavailableException extends RuntimeException {
        public StoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Renders an issued certificate as a bilingual document with its QR code (CC-082, CC-083). */
    public interface CertificateRenderer {

        /** The rendered document and its media type. */
        /**
         * A final class, not a record: SpotBugs misreads a record whose constructor copies an array. The bytes are
         * copied in and out, so callers cannot change a rendered certificate.
         */
        final class Rendered {
            private final byte[] content;
            private final String mediaType;

            public Rendered(byte[] content, String mediaType) {
                this.content = content.clone();
                this.mediaType = mediaType;
            }

            public byte[] content() {
                return content.clone();
            }

            public String mediaType() {
                return mediaType;
            }
        }

        Rendered render(Certificate.Template template, Certificate.Issued certificate, String bodyNl, String bodyEn,
                        String verificationUrl);
    }
}

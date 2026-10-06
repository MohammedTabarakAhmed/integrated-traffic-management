package gov.itms.document.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A stored document, photo or media file (CC-080, CC-085; ADR-051) with its type, owner, expiry date and
 * classification. Its content lives in the object store under {@link #objectKey()}; the database keeps the metadata
 * and the SHA-256 of the content (tamper evidence).
 *
 * <p>PENDING_UPLOAD → AVAILABLE (content received, type and size allowed, malware scan clean) or QUARANTINED (the scan
 * found malware; the content is never stored and never served). Content is written once; a new version is a new
 * document.
 */
public final class Document {

    public enum Status { PENDING_UPLOAD, AVAILABLE, QUARANTINED }

    public enum Classification { PUBLIC, INTERNAL, CONFIDENTIAL, SECRET }

    public static final String CODE = "^[A-Z][A-Z0-9_]{1,39}$";
    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    /** What the uploader declares (CC-080). */
    public record Spec(String type, String ownerType, String ownerId, String title, Classification classification,
                       LocalDate expiryDate) {
        public Spec {
            if (type == null || !CODE_PATTERN.matcher(type).matches()) {
                throw invalid("type");
            }
            if (ownerType == null || !CODE_PATTERN.matcher(ownerType).matches()) {
                throw invalid("ownerType");
            }
            if (ownerId == null || !ID.matcher(ownerId).matches()) {
                throw invalid("ownerId");
            }
            if (title != null && (title.isBlank() || title.length() > 200
                    || title.chars().anyMatch(Character::isISOControl))) {
                throw invalid("title");
            }
        }
    }

    /** A related business record (CC-085), for example an accident case or a violation. */
    public record Link(String recordType, String recordId, Actor linkedBy, Instant linkedAt) {
        public Link {
            if (recordType == null || !CODE_PATTERN.matcher(recordType).matches()) {
                throw invalid("recordType");
            }
            if (recordId == null || !ID.matcher(recordId).matches()) {
                throw invalid("recordId");
            }
        }
    }

    private final UUID id;
    private final Spec spec;
    private final Actor createdBy;
    private final Instant createdAt;
    private Status status;
    private String mediaType;
    private long size;
    private String sha256;
    private String objectKey;
    private String scanResult;
    private Instant availableAt;

    private Document(UUID id, Spec spec, Actor createdBy, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.spec = Objects.requireNonNull(spec);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static Document create(UUID id, Spec spec, Actor by, Instant now) {
        Document d = new Document(id, spec, by, now);
        d.status = Status.PENDING_UPLOAD;
        return d;
    }

    public static Document restore(UUID id, Spec spec, Actor createdBy, Instant createdAt, Status status,
                                   String mediaType, long size, String sha256, String objectKey, String scanResult,
                                   Instant availableAt) {
        Document d = new Document(id, spec, createdBy, createdAt);
        d.status = status;
        d.mediaType = mediaType;
        d.size = size;
        d.sha256 = sha256;
        d.objectKey = objectKey;
        d.scanResult = scanResult;
        d.availableAt = availableAt;
        return d;
    }

    public void requirePendingUpload() {
        if (status != Status.PENDING_UPLOAD) {
            throw DomainException.conflict("CONTENT_ALREADY_UPLOADED", "the document already has content ("
                    + status + ")", List.of("CC-080"), status.name());
        }
    }

    /** The content passed every check and is stored. */
    public void available(String type, long bytes, String hash, String key, String scan, Instant now) {
        requirePendingUpload();
        mediaType = Objects.requireNonNull(type);
        size = bytes;
        sha256 = Objects.requireNonNull(hash);
        objectKey = Objects.requireNonNull(key);
        scanResult = scan;
        availableAt = now;
        status = Status.AVAILABLE;
    }

    /** The malware scan found something (CC-081): the content is discarded and never served. */
    public void quarantined(String type, long bytes, String hash, String finding) {
        requirePendingUpload();
        mediaType = type;
        size = bytes;
        sha256 = hash;
        scanResult = finding;
        status = Status.QUARANTINED;
    }

    public void requireAvailable() {
        if (status != Status.AVAILABLE) {
            throw DomainException.conflict("DOCUMENT_NOT_AVAILABLE", "the document has no usable content (" + status
                    + ")", List.of("CC-080", "CC-081"), status.name());
        }
    }

    static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("CC-080"),
                field);
    }

    public UUID id() {
        return id;
    }

    public Spec spec() {
        return spec;
    }

    public Actor createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    public String mediaType() {
        return mediaType;
    }

    public long size() {
        return size;
    }

    public String sha256() {
        return sha256;
    }

    public String objectKey() {
        return objectKey;
    }

    public String scanResult() {
        return scanResult;
    }

    public Instant availableAt() {
        return availableAt;
    }
}

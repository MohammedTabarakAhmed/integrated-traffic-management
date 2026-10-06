package gov.itms.document.application;

import gov.itms.document.domain.Actor;
import gov.itms.document.domain.Document;
import gov.itms.document.domain.DomainException;
import gov.itms.document.domain.MediaTypes;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Documents and media (CC-080, CC-081, CC-085; ADR-051).
 * <ul>
 *   <li>a document is declared first (type, owner, title, classification, expiry) and its content uploaded once;</li>
 *   <li>each document type has configured allowed file types (checked on the real bytes, not the name) and a maximum
 *       size (CC-081); content is streamed to a temporary file, never held in memory, so large media fit (CC-085);</li>
 *   <li>every upload is scanned for malware before it is stored; infected content is discarded and the document
 *       QUARANTINED (CC-081);</li>
 *   <li>documents link to any number of business records (CC-085);</li>
 *   <li>staff need {@value #READ} (and {@value #READ_CONFIDENTIAL} for confidential or secret documents); a customer
 *       reaches the documents they own (owner type CUSTOMER, owner id = their login subject); every read is audited.</li>
 * </ul>
 */
public class DocumentService {

    public static final String CREATE = "document.create";
    public static final String READ = "document.read";
    public static final String READ_CONFIDENTIAL = "document.read.confidential";
    public static final String CUSTOMER_OWNER = "CUSTOMER";
    static final String ENTITY = "Document";
    private static final int HEAD = 16;

    /** Upload rules of one document type (CC-081), from configuration. */
    public record TypePolicy(String code, Set<String> mediaTypes, long maxBytes,
                             Document.Classification classification) {
        public TypePolicy {
            mediaTypes = Set.copyOf(mediaTypes);
        }
    }

    /** Content being served. */
    public record Content(Document document, InputStream stream) {
    }

    private final Ports.DocumentRepository repo;
    private final Ports.ContentStore store;
    private final Ports.MalwareScanner scanner;
    private final Map<String, TypePolicy> policies;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public DocumentService(Ports.DocumentRepository repo, Ports.ContentStore store, Ports.MalwareScanner scanner,
                           List<TypePolicy> policies, AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.repo = repo;
        this.store = store;
        this.scanner = scanner;
        Map<String, TypePolicy> m = new LinkedHashMap<>();
        policies.forEach(p -> m.put(p.code(), p));
        this.policies = Map.copyOf(m);
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public List<TypePolicy> types() {
        return List.copyOf(policies.values());
    }

    @Transactional
    public Document create(Document.Spec spec, Caller caller) {
        channelOf(caller);
        require(caller.grants().holds(CREATE) || ownsAsCustomer(spec, caller.actor()));
        TypePolicy policy = policy(spec.type());
        Document.Spec effective = spec.classification() != null ? spec
                : new Document.Spec(spec.type(), spec.ownerType(), spec.ownerId(), spec.title(),
                policy.classification(), spec.expiryDate());
        Document d = Document.create(UUID.randomUUID(), effective, caller.actor(), clock.instant());
        repo.insert(d);
        audit.record("DOCUMENT_CREATED", ENTITY, d.id().toString(), null, snapshot(d), null);
        return d;
    }

    /**
     * Receives the content (CC-081): at most the type's size, an allowed real file type, a clean malware scan. Only
     * the person who declared the document uploads its content.
     */
    @Transactional(noRollbackFor = DomainException.class)
    public Document upload(UUID id, InputStream body, Caller caller) {
        channelOf(caller);
        Document d = repo.findForUpdate(id).orElseThrow(() -> notFound(id));
        if (!d.createdBy().id().equals(caller.actor().id())) {
            throw outOfScope();
        }
        d.requirePendingUpload();
        TypePolicy policy = policy(d.spec().type());
        Path temp = null;
        try {
            temp = Files.createTempFile("itms-upload-", ".bin");
            Received received = receive(body, temp, policy.maxBytes());
            String mediaType = MediaTypes.sniff(received.head(), received.headLength())
                    .filter(policy.mediaTypes()::contains)
                    .orElseThrow(() -> DomainException.validation("FILE_TYPE_NOT_ALLOWED",
                            "this file type is not allowed for " + policy.code(), List.of("CC-081"),
                            String.join(", ", policy.mediaTypes().stream().sorted().toList())));
            Ports.MalwareScanner.Result scan = scanner.scan(temp);
            Instant now = clock.instant();
            if (!scan.clean()) {
                d.quarantined(mediaType, received.size(), received.sha256(), scan.finding());
                repo.saveContent(d);
                audit.record("DOCUMENT_QUARANTINED", ENTITY, id.toString(), null,
                        Map.of("finding", scan.finding(), "size", received.size()), null);
                events.record("itms.document.quarantined", id.toString(), Map.of("documentId", id.toString(),
                        "type", d.spec().type(), "uploadedBy", caller.actor().id()));
                throw DomainException.validation("MALWARE_FOUND", "the file contains malware and was rejected",
                        List.of("CC-081"));
            }
            String key = "documents/" + id;
            store.put(key, temp, mediaType, received.sha256());
            d.available(mediaType, received.size(), received.sha256(), key, "CLEAN", now);
            repo.saveContent(d);
            audit.record("DOCUMENT_UPLOADED", ENTITY, id.toString(), null, snapshot(d), null);
            events.record("itms.document.available", id.toString(), snapshot(d));
            return d;
        } catch (IOException e) {
            throw new UncheckedIOException("upload could not be buffered", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // the temporary directory is cleaned by the platform; nothing else to do
                }
            }
        }
    }

    @Transactional
    public Document get(UUID id, Caller caller) {
        Document d = repo.find(id).orElseThrow(() -> notFound(id));
        requireReads(d, caller);
        audit.record("DOCUMENT_VIEWED", ENTITY, id.toString(), null, null, null);
        return d;
    }

    @Transactional
    public Content content(UUID id, Caller caller) {
        Document d = repo.find(id).orElseThrow(() -> notFound(id));
        requireReads(d, caller);
        d.requireAvailable();
        audit.record("DOCUMENT_CONTENT_VIEWED", ENTITY, id.toString(), null, null, null);
        return new Content(d, store.get(d.objectKey()));
    }

    @Transactional
    public Document.Link link(UUID id, String recordType, String recordId, Caller caller) {
        channelOf(caller);
        require(caller.grants().holds(CREATE));
        repo.find(id).orElseThrow(() -> notFound(id));
        Document.Link link = new Document.Link(recordType, recordId, caller.actor(), clock.instant());
        repo.insertLink(id, link);
        audit.record("DOCUMENT_LINKED", ENTITY, id.toString(), null,
                Map.of("recordType", recordType, "recordId", recordId), null);
        return link;
    }

    @Transactional(readOnly = true)
    public List<Document.Link> links(UUID id, Caller caller) {
        Document d = repo.find(id).orElseThrow(() -> notFound(id));
        requireReads(d, caller);
        return repo.links(id);
    }

    @Transactional
    public List<Document> byOwner(String ownerType, String ownerId, int limit, Caller caller) {
        boolean own = CUSTOMER_OWNER.equals(ownerType) && caller.actor().id().equals(ownerId);
        require(own || caller.grants().holds(READ));
        checkLimit(limit);
        List<Document> found = repo.byOwner(ownerType, ownerId, limit).stream()
                .filter(d -> own || mayReadClass(d, caller)).toList();
        if (!own) {
            audit.record("DOCUMENTS_LISTED", "DocumentOwner", ownerType + ":" + ownerId, null,
                    Map.of("count", found.size()), null);
        }
        return found;
    }

    @Transactional
    public List<Document> byRecord(String recordType, String recordId, int limit, Caller caller) {
        require(caller.grants().holds(READ));
        checkLimit(limit);
        List<Document> found = repo.byRecord(recordType, recordId, limit).stream()
                .filter(d -> mayReadClass(d, caller)).toList();
        audit.record("DOCUMENTS_LISTED", "DocumentRecord", recordType + ":" + recordId, null,
                Map.of("count", found.size()), null);
        return found;
    }

    // ---- helpers

    private record Received(long size, String sha256, byte[] head, int headLength) {
    }

    private static Received receive(InputStream body, Path temp, long maxBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] head = new byte[HEAD];
        int headLength = 0;
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(temp)) {
            int n;
            while ((n = body.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw DomainException.validation("FILE_TOO_LARGE", "the file is larger than allowed",
                            List.of("CC-081"), maxBytes);
                }
                if (headLength < HEAD) {
                    int copy = Math.min(HEAD - headLength, n);
                    System.arraycopy(buffer, 0, head, headLength, copy);
                    headLength += copy;
                }
                digest.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        }
        if (total == 0) {
            throw DomainException.validation("FILE_EMPTY", "the file is empty", List.of("CC-081"));
        }
        return new Received(total, HexFormat.of().formatHex(digest.digest()), head, headLength);
    }

    private TypePolicy policy(String type) {
        return Optional.ofNullable(policies.get(type)).orElseThrow(() -> DomainException.validation(
                "DOCUMENT_TYPE_UNKNOWN", "document type " + type + " is not configured", List.of("CC-081"), type));
    }

    private void requireReads(Document d, Caller caller) {
        if (ownsAsCustomer(d.spec(), caller.actor())) {
            return;
        }
        require(caller.grants().holds(READ) && mayReadClass(d, caller));
    }

    private static boolean mayReadClass(Document d, Caller caller) {
        Document.Classification c = d.spec().classification();
        return c == Document.Classification.PUBLIC || c == Document.Classification.INTERNAL
                || caller.grants().holds(READ_CONFIDENTIAL);
    }

    private static boolean ownsAsCustomer(Document.Spec spec, Actor actor) {
        return CUSTOMER_OWNER.equals(spec.ownerType()) && actor.id().equals(spec.ownerId());
    }

    static Map<String, Object> snapshot(Document d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentId", d.id().toString());
        m.put("type", d.spec().type());
        m.put("ownerType", d.spec().ownerType());
        m.put("ownerId", d.spec().ownerId());
        m.put("classification", d.spec().classification() == null ? null : d.spec().classification().name());
        m.put("status", d.status().name());
        m.put("mediaType", d.mediaType());
        m.put("size", d.size());
        m.put("sha256", d.sha256());
        return m;
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1..200", List.of("CC-080"), "limit");
        }
    }

    static void require(boolean allowed) {
        if (!allowed) {
            throw outOfScope();
        }
    }

    static DomainException outOfScope() {
        return new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                "you may not do this with this document", List.of("CC-001"));
    }

    static void channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw DomainException.validation("CHANNEL_REQUIRED", "the request does not name its channel (X-Channel)",
                    List.of("CC-120"));
        }
    }

    private static DomainException notFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "DOCUMENT_NOT_FOUND",
                "document " + id + " not found", List.of("CC-080"));
    }
}

package gov.itms.document.infrastructure;

import gov.itms.document.application.Ports;
import gov.itms.document.domain.Actor;
import gov.itms.document.domain.Certificate;
import gov.itms.document.domain.Document;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Document and certificate metadata in PostgreSQL; guard triggers keep content and certificates tamper-evident. */
class JdbcDocumentRepository implements Ports.DocumentRepository {

    private static final String DOCUMENT_SELECT = """
            select id, type, owner_type, owner_id, title, classification, expiry_date, status, media_type, size_bytes,
                sha256, object_key, scan_result, created_by, created_by_username, created_at, available_at
            from document""";
    private static final String TEMPLATE_SELECT = """
            select code, version_no, title_nl, title_en, body_nl, body_en, validity_days, published_by,
                published_by_username, published_at
            from certificate_template""";
    private static final String CERTIFICATE_SELECT = """
            select number, verification_code, template_code, template_version, holder_name_nl, holder_name_en,
                subject_type, subject_id, data::text as data, reference, issued_on, valid_until, status, revoked_at,
                revocation_reason, document_id, issued_by, issued_by_username, issued_at
            from certificate""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcDocumentRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(Document d) {
        Document.Spec s = d.spec();
        jdbc.sql("""
                        insert into document (id, type, owner_type, owner_id, title, classification, expiry_date, status,
                            created_by, created_by_username, created_at)
                        values (:id, :type, :ownerType, :ownerId, :title, :classification, :expiry, :status, :by,
                            :byName, :at)""")
                .param("id", d.id())
                .param("type", s.type())
                .param("ownerType", s.ownerType())
                .param("ownerId", s.ownerId())
                .param("title", s.title())
                .param("classification", s.classification().name())
                .param("expiry", s.expiryDate() == null ? null : Date.valueOf(s.expiryDate()))
                .param("status", d.status().name())
                .param("by", d.createdBy().id())
                .param("byName", d.createdBy().username())
                .param("at", Timestamp.from(d.createdAt()))
                .update();
    }

    @Override
    public Optional<Document> find(UUID id) {
        return jdbc.sql(DOCUMENT_SELECT + " where id = :id").param("id", id).query(this::mapDocument).optional();
    }

    @Override
    public Optional<Document> findForUpdate(UUID id) {
        return jdbc.sql(DOCUMENT_SELECT + " where id = :id for update").param("id", id).query(this::mapDocument)
                .optional();
    }

    @Override
    public void saveContent(Document d) {
        jdbc.sql("""
                        update document set status = :status, media_type = :mediaType, size_bytes = :size,
                            sha256 = :sha, object_key = :key, scan_result = :scan, available_at = :availableAt
                        where id = :id and status = 'PENDING_UPLOAD'""")
                .param("status", d.status().name())
                .param("mediaType", d.mediaType())
                .param("size", d.size())
                .param("sha", d.sha256())
                .param("key", d.objectKey())
                .param("scan", d.scanResult())
                .param("availableAt", d.availableAt() == null ? null : Timestamp.from(d.availableAt()))
                .param("id", d.id())
                .update();
    }

    @Override
    public void insertLink(UUID documentId, Document.Link link) {
        jdbc.sql("""
                        insert into document_link (document_id, record_type, record_id, linked_by, linked_by_username,
                            linked_at)
                        values (:id, :type, :record, :by, :byName, :at) on conflict do nothing""")
                .param("id", documentId)
                .param("type", link.recordType())
                .param("record", link.recordId())
                .param("by", link.linkedBy().id())
                .param("byName", link.linkedBy().username())
                .param("at", Timestamp.from(link.linkedAt()))
                .update();
    }

    @Override
    public List<Document.Link> links(UUID documentId) {
        return jdbc.sql("""
                        select record_type, record_id, linked_by, linked_by_username, linked_at
                        from document_link where document_id = :id order by linked_at""")
                .param("id", documentId)
                .query((rs, n) -> new Document.Link(rs.getString("record_type"), rs.getString("record_id"),
                        new Actor(rs.getString("linked_by"), rs.getString("linked_by_username")),
                        rs.getTimestamp("linked_at").toInstant()))
                .list();
    }

    @Override
    public List<Document> byOwner(String ownerType, String ownerId, int limit) {
        return jdbc.sql(DOCUMENT_SELECT + " where owner_type = :t and owner_id = :o order by created_at desc limit :l")
                .param("t", ownerType).param("o", ownerId).param("l", limit).query(this::mapDocument).list();
    }

    @Override
    public List<Document> byRecord(String recordType, String recordId, int limit) {
        return jdbc.sql(DOCUMENT_SELECT + """
                          where id in (select document_id from document_link where record_type = :t and record_id = :r)
                         order by created_at desc limit :l""")
                .param("t", recordType).param("r", recordId).param("l", limit).query(this::mapDocument).list();
    }

    @Override
    public void lockTemplate(String code) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('certificate-template:' || :c))").param("c", code).query()
                .listOfRows();
    }

    @Override
    public int nextTemplateVersion(String code) {
        return jdbc.sql("select coalesce(max(version_no), 0) + 1 from certificate_template where code = :c")
                .param("c", code).query(Integer.class).single();
    }

    @Override
    public void insertTemplate(Certificate.Template t) {
        jdbc.sql("""
                        insert into certificate_template (code, version_no, title_nl, title_en, body_nl, body_en,
                            validity_days, published_by, published_by_username, published_at)
                        values (:code, :no, :titleNl, :titleEn, :bodyNl, :bodyEn, :validity, :by, :byName, :at)""")
                .param("code", t.code())
                .param("no", t.versionNo())
                .param("titleNl", t.titleNl())
                .param("titleEn", t.titleEn())
                .param("bodyNl", t.bodyNl())
                .param("bodyEn", t.bodyEn())
                .param("validity", t.validityDays())
                .param("by", t.publishedBy().id())
                .param("byName", t.publishedBy().username())
                .param("at", Timestamp.from(t.publishedAt()))
                .update();
    }

    @Override
    public Optional<Certificate.Template> latestTemplate(String code) {
        return jdbc.sql(TEMPLATE_SELECT + " where code = :c order by version_no desc limit 1").param("c", code)
                .query(JdbcDocumentRepository::mapTemplate).optional();
    }

    @Override
    public List<Certificate.Template> latestTemplates() {
        return jdbc.sql(TEMPLATE_SELECT + " " + """
                         t where version_no = (select max(version_no) from certificate_template x
                             where x.code = t.code) order by code""")
                .query(JdbcDocumentRepository::mapTemplate).list();
    }

    @Override
    public long nextCertificateSequence() {
        return jdbc.sql("select nextval('certificate_seq')").query(Long.class).single();
    }

    @Override
    public void insertCertificate(Certificate.Issued c) {
        jdbc.sql("""
                        insert into certificate (number, verification_code, template_code, template_version,
                            holder_name_nl, holder_name_en, subject_type, subject_id, data, reference, issued_on,
                            valid_until, status, document_id, issued_by, issued_by_username, issued_at)
                        values (:number, :code, :template, :version, :nameNl, :nameEn, :subjectType, :subjectId,
                            cast(:data as jsonb), :reference, :issuedOn, :validUntil, :status, :documentId, :by,
                            :byName, :at)""")
                .param("number", c.number())
                .param("code", c.verificationCode())
                .param("template", c.templateCode())
                .param("version", c.templateVersion())
                .param("nameNl", c.holderNameNl())
                .param("nameEn", c.holderNameEn())
                .param("subjectType", c.subjectType())
                .param("subjectId", c.subjectId())
                .param("data", json.writeValueAsString(c.data()))
                .param("reference", c.reference())
                .param("issuedOn", Date.valueOf(c.issuedOn()))
                .param("validUntil", c.validUntil() == null ? null : Date.valueOf(c.validUntil()))
                .param("status", c.status().name())
                .param("documentId", c.documentId())
                .param("by", c.issuedBy().id())
                .param("byName", c.issuedBy().username())
                .param("at", Timestamp.from(c.issuedAt()))
                .update();
    }

    @Override
    public Optional<Certificate.Issued> findCertificate(String number) {
        return jdbc.sql(CERTIFICATE_SELECT + " where number = :n").param("n", number).query(this::mapCertificate)
                .optional();
    }

    @Override
    public Optional<Certificate.Issued> findCertificateForUpdate(String number) {
        return jdbc.sql(CERTIFICATE_SELECT + " where number = :n for update").param("n", number)
                .query(this::mapCertificate).optional();
    }

    @Override
    public void saveRevocation(Certificate.Issued c) {
        jdbc.sql("""
                        update certificate set status = :status, revoked_at = :at, revocation_reason = :reason
                        where number = :n""")
                .param("status", c.status().name())
                .param("at", Timestamp.from(c.revokedAt()))
                .param("reason", c.revocationReason())
                .param("n", c.number())
                .update();
    }

    private Document mapDocument(ResultSet rs, int row) throws SQLException {
        Date expiry = rs.getDate("expiry_date");
        Document.Spec spec = new Document.Spec(rs.getString("type"), rs.getString("owner_type"),
                rs.getString("owner_id"), rs.getString("title"),
                Document.Classification.valueOf(rs.getString("classification")),
                expiry == null ? null : expiry.toLocalDate());
        Timestamp available = rs.getTimestamp("available_at");
        return Document.restore(rs.getObject("id", UUID.class), spec,
                new Actor(rs.getString("created_by"), rs.getString("created_by_username")),
                rs.getTimestamp("created_at").toInstant(), Document.Status.valueOf(rs.getString("status")),
                rs.getString("media_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("object_key"), rs.getString("scan_result"),
                available == null ? null : available.toInstant());
    }

    private static Certificate.Template mapTemplate(ResultSet rs, int row) throws SQLException {
        int days = rs.getInt("validity_days");
        Integer validity = rs.wasNull() ? null : days; // wasNull refers to the last column read
        return new Certificate.Template(rs.getString("code"), rs.getInt("version_no"), rs.getString("title_nl"),
                rs.getString("title_en"), rs.getString("body_nl"), rs.getString("body_en"),
                validity,
                new Actor(rs.getString("published_by"), rs.getString("published_by_username")),
                rs.getTimestamp("published_at").toInstant());
    }

    private Certificate.Issued mapCertificate(ResultSet rs, int row) throws SQLException {
        Map<String, String> data = new LinkedHashMap<>();
        json.readTree(rs.getString("data")).properties().forEach(e -> data.put(e.getKey(), e.getValue().asString()));
        Date validUntil = rs.getDate("valid_until");
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new Certificate.Issued(rs.getString("number"), rs.getString("verification_code"),
                rs.getString("template_code"), rs.getInt("template_version"), rs.getString("holder_name_nl"),
                rs.getString("holder_name_en"), rs.getString("subject_type"), rs.getString("subject_id"), data,
                rs.getString("reference"), rs.getDate("issued_on").toLocalDate(),
                validUntil == null ? null : validUntil.toLocalDate(),
                Certificate.Status.valueOf(rs.getString("status")), revoked == null ? null : revoked.toInstant(),
                rs.getString("revocation_reason"), rs.getObject("document_id", UUID.class),
                new Actor(rs.getString("issued_by"), rs.getString("issued_by_username")),
                rs.getTimestamp("issued_at").toInstant());
    }
}

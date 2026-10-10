package gov.itms.licensing.infrastructure;

import gov.itms.licensing.application.ForeignRepository;
import gov.itms.licensing.domain.Foreign;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exchange rules, foreign licences and permits in PostgreSQL; the database guards them (V7). */
class JdbcForeignRepository implements ForeignRepository {

    private static final String RULE = """
            select id, country_code, mode, class_map::text as class_map, status, submitted_by, submitted_at,
                submit_reason, decided_by, decided_at, decision_reason
            from exchange_country_rule""";
    private static final String FOREIGN = """
            select id, traffic_file_number, country_code, foreign_number, classes::text as classes, issued_on,
                expires_on, language, document_id, translation_document_id, status, licence_id, recorded_by,
                recorded_at
            from foreign_licence""";
    private static final String PERMIT = """
            select id, permit_number, kind, traffic_file_number, region, licence_id, foreign_licence_id,
                classes::text as classes, valid_from, valid_until, status, request_id, issued_at
            from temporary_permit""";
    private static final String IDP = """
            select id, permit_number, licence_id, issued_on, valid_until, printer, status, printed_by, printed_at,
                request_id, issued_at
            from international_permit""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcForeignRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void lockCountry(String countryCode) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('exchange-country:' || :c))").param("c", countryCode)
                .query().listOfRows();
    }

    // ---- rules

    @Override
    public void insertRule(Foreign.CountryRule r) {
        jdbc.sql("""
                        insert into exchange_country_rule (id, country_code, mode, class_map, status, submitted_by,
                            submitted_at, submit_reason)
                        values (:id, :country, :mode, cast(:map as jsonb), :status, :by, :at, :reason)""")
                .param("id", r.id()).param("country", r.countryCode()).param("mode", r.mode().name())
                .param("map", json.writeValueAsString(r.classMap())).param("status", r.status().name())
                .param("by", r.submittedBy()).param("at", Timestamp.from(r.submittedAt()))
                .param("reason", r.submitReason()).update();
    }

    @Override
    public void updateRule(Foreign.CountryRule r) {
        jdbc.sql("""
                        update exchange_country_rule set status = :status, decided_by = :by, decided_at = :at,
                            decision_reason = :reason
                        where id = :id""")
                .param("status", r.status().name()).param("by", r.decidedBy()).param("at", ts(r.decidedAt()))
                .param("reason", r.decisionReason()).param("id", r.id()).update();
    }

    @Override
    public Optional<Foreign.CountryRule> rule(UUID id) {
        return jdbc.sql(RULE + " where id = :id").param("id", id).query(this::mapRule).optional();
    }

    @Override
    public Optional<Foreign.CountryRule> ruleForUpdate(UUID id) {
        return jdbc.sql(RULE + " where id = :id for update").param("id", id).query(this::mapRule).optional();
    }

    @Override
    public List<Foreign.CountryRule> rules(String countryCode) {
        return jdbc.sql(RULE + " where (cast(:c as varchar) is null or country_code = :c)"
                        + " order by country_code, submitted_at desc")
                .param("c", countryCode).query(this::mapRule).list();
    }

    @Override
    public Optional<Foreign.CountryRule> approvedRule(String countryCode) {
        return jdbc.sql(RULE + " where country_code = :c and status = 'APPROVED'").param("c", countryCode)
                .query(this::mapRule).optional();
    }

    private Foreign.CountryRule mapRule(ResultSet rs, int n) throws SQLException {
        Map<String, String> map = new LinkedHashMap<>();
        json.readTree(rs.getString("class_map")).properties().forEach(e -> map.put(e.getKey(),
                e.getValue().asString()));
        return new Foreign.CountryRule(rs.getObject("id", UUID.class), rs.getString("country_code"),
                Foreign.ExchangeMode.valueOf(rs.getString("mode")), map,
                Foreign.RuleStatus.valueOf(rs.getString("status")), rs.getString("submitted_by"),
                rs.getTimestamp("submitted_at").toInstant(), rs.getString("submit_reason"),
                rs.getString("decided_by"), instant(rs, "decided_at"), rs.getString("decision_reason"));
    }

    // ---- foreign licences

    @Override
    public boolean insertForeign(Foreign.ForeignLicence f) {
        return jdbc.sql("""
                        insert into foreign_licence (id, traffic_file_number, country_code, foreign_number, classes,
                            issued_on, expires_on, language, document_id, translation_document_id, status,
                            recorded_by, recorded_at)
                        values (:id, :file, :country, :number, cast(:classes as jsonb), :issued, :expires, :lang,
                            :doc, :translation, :status, :by, :at)
                        on conflict (country_code, foreign_number) do nothing""")
                .param("id", f.id()).param("file", f.trafficFileNumber()).param("country", f.countryCode())
                .param("number", f.foreignNumber()).param("classes", json.writeValueAsString(f.classes()))
                .param("issued", date(f.issuedOn())).param("expires", date(f.expiresOn()))
                .param("lang", f.language()).param("doc", f.documentId())
                .param("translation", f.translationDocumentId()).param("status", f.status().name())
                .param("by", f.recordedBy()).param("at", Timestamp.from(f.recordedAt())).update() == 1;
    }

    @Override
    public void updateForeign(Foreign.ForeignLicence f) {
        jdbc.sql("update foreign_licence set status = :status, licence_id = :licence where id = :id")
                .param("status", f.status().name()).param("licence", f.licenceId()).param("id", f.id()).update();
    }

    @Override
    public Optional<Foreign.ForeignLicence> foreign(UUID id) {
        return jdbc.sql(FOREIGN + " where id = :id").param("id", id).query(this::mapForeign).optional();
    }

    @Override
    public Optional<Foreign.ForeignLicence> foreignForUpdate(UUID id) {
        return jdbc.sql(FOREIGN + " where id = :id for update").param("id", id).query(this::mapForeign).optional();
    }

    @Override
    public List<Foreign.ForeignLicence> foreignOf(String trafficFileNumber) {
        return jdbc.sql(FOREIGN + " where traffic_file_number = :t order by recorded_at desc")
                .param("t", trafficFileNumber).query(this::mapForeign).list();
    }

    @Override
    public List<Foreign.ForeignLicence> foreignMasterFile(String countryCode, LocalDate expiringBefore, int offset,
                                                          int limit) {
        return jdbc.sql(FOREIGN + " where (cast(:c as varchar) is null or country_code = :c)"
                        + " and (cast(:d as date) is null or expires_on < :d)"
                        + " order by expires_on, id offset :o limit :n")
                .param("c", countryCode).param("d", date(expiringBefore)).param("o", offset).param("n", limit)
                .query(this::mapForeign).list();
    }

    private Foreign.ForeignLicence mapForeign(ResultSet rs, int n) throws SQLException {
        return new Foreign.ForeignLicence(rs.getObject("id", UUID.class), rs.getString("traffic_file_number"),
                rs.getString("country_code"), rs.getString("foreign_number"), list(rs.getString("classes")),
                localDate(rs, "issued_on"), localDate(rs, "expires_on"), rs.getString("language"),
                rs.getString("document_id"), rs.getString("translation_document_id"),
                Foreign.ForeignStatus.valueOf(rs.getString("status")), rs.getObject("licence_id", UUID.class),
                rs.getString("recorded_by"), rs.getTimestamp("recorded_at").toInstant());
    }

    // ---- temporary permits

    @Override
    public void insertPermit(Foreign.TemporaryPermit p) {
        jdbc.sql("""
                        insert into temporary_permit (id, permit_number, kind, traffic_file_number, region,
                            licence_id, foreign_licence_id, classes, valid_from, valid_until, status, request_id,
                            issued_at)
                        values (:id, :number, :kind, :file, :region, :licence, :foreign, cast(:classes as jsonb),
                            :from, :until, :status, :request, :at)""")
                .param("id", p.id()).param("number", p.permitNumber()).param("kind", p.kind().name())
                .param("file", p.trafficFileNumber()).param("region", p.region()).param("licence", p.licenceId())
                .param("foreign", p.foreignLicenceId()).param("classes", json.writeValueAsString(p.classes()))
                .param("from", date(p.validFrom())).param("until", date(p.validUntil()))
                .param("status", p.status().name()).param("request", p.requestId())
                .param("at", Timestamp.from(p.issuedAt())).update();
    }

    @Override
    public void updatePermit(Foreign.TemporaryPermit p) {
        jdbc.sql("update temporary_permit set status = :status where id = :id").param("status", p.status().name())
                .param("id", p.id()).update();
    }

    @Override
    public Optional<Foreign.TemporaryPermit> permit(UUID id) {
        return jdbc.sql(PERMIT + " where id = :id").param("id", id).query(this::mapPermit).optional();
    }

    @Override
    public Optional<Foreign.TemporaryPermit> permitForUpdate(UUID id) {
        return jdbc.sql(PERMIT + " where id = :id for update").param("id", id).query(this::mapPermit).optional();
    }

    @Override
    public List<Foreign.TemporaryPermit> permitsOf(String trafficFileNumber) {
        return jdbc.sql(PERMIT + " where traffic_file_number = :t order by issued_at desc")
                .param("t", trafficFileNumber).query(this::mapPermit).list();
    }

    @Override
    public List<Foreign.TemporaryPermit> permitMasterFile(LocalDate expiringBefore, int offset, int limit) {
        return jdbc.sql(PERMIT + " where (cast(:d as date) is null or valid_until < :d)"
                        + " order by valid_until, id offset :o limit :n")
                .param("d", date(expiringBefore)).param("o", offset).param("n", limit).query(this::mapPermit)
                .list();
    }

    private Foreign.TemporaryPermit mapPermit(ResultSet rs, int n) throws SQLException {
        return new Foreign.TemporaryPermit(rs.getObject("id", UUID.class), rs.getString("permit_number"),
                Foreign.PermitKind.valueOf(rs.getString("kind")), rs.getString("traffic_file_number"),
                rs.getString("region"), rs.getObject("licence_id", UUID.class),
                rs.getObject("foreign_licence_id", UUID.class), list(rs.getString("classes")),
                localDate(rs, "valid_from"), localDate(rs, "valid_until"),
                Foreign.PermitStatus.valueOf(rs.getString("status")), rs.getObject("request_id", UUID.class),
                rs.getTimestamp("issued_at").toInstant());
    }

    // ---- international permits

    @Override
    public String nextIdpNumber(int year) {
        long n = jdbc.sql("select nextval('international_permit_seq')").query(Long.class).single();
        return String.format("IDP-%d-%06d", year, n);
    }

    @Override
    public void insertIdp(Foreign.InternationalPermit p) {
        jdbc.sql("""
                        insert into international_permit (id, permit_number, licence_id, issued_on, valid_until,
                            printer, status, request_id, issued_at)
                        values (:id, :number, :licence, :issued, :until, :printer, :status, :request, :at)""")
                .param("id", p.id()).param("number", p.permitNumber()).param("licence", p.licenceId())
                .param("issued", date(p.issuedOn())).param("until", date(p.validUntil()))
                .param("printer", p.printer()).param("status", p.status().name()).param("request", p.requestId())
                .param("at", Timestamp.from(p.issuedAt())).update();
    }

    @Override
    public void updateIdp(Foreign.InternationalPermit p) {
        jdbc.sql("update international_permit set status = :status, printed_by = :by, printed_at = :at"
                        + " where id = :id")
                .param("status", p.status().name()).param("by", p.printedBy()).param("at", ts(p.printedAt()))
                .param("id", p.id()).update();
    }

    @Override
    public Optional<Foreign.InternationalPermit> idpForUpdate(UUID id) {
        return jdbc.sql(IDP + " where id = :id for update").param("id", id).query(this::mapIdp).optional();
    }

    @Override
    public List<Foreign.InternationalPermit> idpsOf(UUID licenceId) {
        return jdbc.sql(IDP + " where licence_id = :l order by issued_at desc").param("l", licenceId)
                .query(this::mapIdp).list();
    }

    private Foreign.InternationalPermit mapIdp(ResultSet rs, int n) throws SQLException {
        return new Foreign.InternationalPermit(rs.getObject("id", UUID.class), rs.getString("permit_number"),
                rs.getObject("licence_id", UUID.class), localDate(rs, "issued_on"), localDate(rs, "valid_until"),
                rs.getString("printer"), Foreign.IdpStatus.valueOf(rs.getString("status")),
                rs.getString("printed_by"), instant(rs, "printed_at"), rs.getObject("request_id", UUID.class),
                rs.getTimestamp("issued_at").toInstant());
    }

    // ---- helpers

    private List<String> list(String text) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : json.readTree(text)) {
            out.add(c.asString());
        }
        return out;
    }

    private static Date date(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}

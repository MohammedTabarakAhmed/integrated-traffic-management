package gov.itms.licensing.infrastructure;

import gov.itms.licensing.application.LicenceClassRepository;
import gov.itms.licensing.domain.Actor;
import gov.itms.licensing.domain.Channel;
import gov.itms.licensing.domain.LicenceClassVersion;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Licence class versions in PostgreSQL; the database guards decisions (V1). */
class JdbcLicenceClassRepository implements LicenceClassRepository {

    private static final String SELECT = """
            select id, class_code, version_no, name_nl, name_en, vehicle_type_nl, vehicle_type_en, gearbox_recorded,
                category_rules::text as category_rules, effective_from, effective_to, status, submitted_at,
                submitted_by, submitted_by_username, submitted_channel, submit_reason, decided_at, decided_by,
                decided_by_username, decided_channel, decision_reason
            from licence_class_version""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcLicenceClassRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public int nextVersionNo(String classCode) {
        return jdbc.sql("select coalesce(max(version_no), 0) + 1 from licence_class_version where class_code = :c")
                .param("c", classCode).query(Integer.class).single();
    }

    @Override
    public void lockClass(String classCode) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('licence-class:' || :c))").param("c", classCode).query()
                .listOfRows();
    }

    @Override
    public void insert(LicenceClassVersion v) {
        LicenceClassVersion.Spec s = v.spec();
        jdbc.sql("""
                        insert into licence_class_version (id, class_code, version_no, name_nl, name_en,
                            vehicle_type_nl, vehicle_type_en, gearbox_recorded, category_rules, effective_from,
                            effective_to, status, submitted_at, submitted_by, submitted_by_username, submitted_channel,
                            submit_reason)
                        values (:id, :code, :no, :nameNl, :nameEn, :vehicleNl, :vehicleEn, :gearbox,
                            cast(:rules as jsonb), :from, :to, 'PENDING', :at, :by, :byName, :channel, :reason)""")
                .param("id", v.id())
                .param("code", s.classCode())
                .param("no", v.versionNo())
                .param("nameNl", s.nameNl())
                .param("nameEn", s.nameEn())
                .param("vehicleNl", s.vehicleTypeNl())
                .param("vehicleEn", s.vehicleTypeEn())
                .param("gearbox", s.gearboxRecorded())
                .param("rules", writeRules(s.rules()))
                .param("from", Date.valueOf(s.effectiveFrom()))
                .param("to", s.effectiveTo() == null ? null : Date.valueOf(s.effectiveTo()))
                .param("at", Timestamp.from(v.submitted().at()))
                .param("by", v.submitted().actor().id())
                .param("byName", v.submitted().actor().username())
                .param("channel", v.submitted().channel().name())
                .param("reason", v.submitted().reason())
                .update();
    }

    @Override
    public Optional<LicenceClassVersion> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<LicenceClassVersion> findForUpdate(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(this::map).optional();
    }

    @Override
    public List<LicenceClassVersion> byClass(String classCode) {
        return jdbc.sql(SELECT + " where class_code = :c order by version_no").param("c", classCode)
                .query(this::map).list();
    }

    @Override
    public List<LicenceClassVersion> approved() {
        return jdbc.sql(SELECT + " where status = 'APPROVED' order by class_code, version_no").query(this::map)
                .list();
    }

    @Override
    public void saveDecision(LicenceClassVersion v) {
        LicenceClassVersion.Decision d = v.decided();
        int updated = jdbc.sql("""
                        update licence_class_version set status = :status, decided_at = :at, decided_by = :by,
                            decided_by_username = :byName, decided_channel = :channel, decision_reason = :reason
                        where id = :id and status = 'PENDING'""")
                .param("status", v.status().name())
                .param("at", Timestamp.from(d.at()))
                .param("by", d.actor().id())
                .param("byName", d.actor().username())
                .param("channel", d.channel().name())
                .param("reason", d.reason())
                .param("id", v.id())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("licence class version " + v.id() + " was decided concurrently");
        }
    }

    @Override
    public void saveEnd(LicenceClassVersion v) {
        jdbc.sql("update licence_class_version set effective_to = :to where id = :id")
                .param("to", Date.valueOf(v.effectiveTo())).param("id", v.id()).update();
    }

    private String writeRules(List<LicenceClassVersion.CategoryRule> rules) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LicenceClassVersion.CategoryRule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("customerCategory", r.customerCategory());
            m.put("minAgeYears", r.minAgeYears());
            m.put("maxAgeYears", r.maxAgeYears());
            m.put("requiredTests", r.requiredTests());
            m.put("minTrainingHours", r.minTrainingHours());
            m.put("medicalRequired", r.medicalRequired());
            m.put("validityMonths", r.validityMonths());
            m.put("residencyLinked", r.residencyLinked());
            out.add(m);
        }
        return json.writeValueAsString(out);
    }

    private List<LicenceClassVersion.CategoryRule> readRules(String text) {
        List<LicenceClassVersion.CategoryRule> rules = new ArrayList<>();
        for (JsonNode r : json.readTree(text)) {
            List<String> tests = new ArrayList<>();
            r.path("requiredTests").forEach(t -> tests.add(t.asString()));
            JsonNode category = r.path("customerCategory");
            JsonNode maxAge = r.path("maxAgeYears");
            rules.add(new LicenceClassVersion.CategoryRule(category.isNull() || category.isMissingNode() ? null
                    : category.asString(), r.path("minAgeYears").asInt(),
                    maxAge.isNull() || maxAge.isMissingNode() ? null : maxAge.asInt(), tests,
                    r.path("minTrainingHours").asInt(), r.path("medicalRequired").asBoolean(),
                    r.path("validityMonths").asInt(), r.path("residencyLinked").asBoolean()));
        }
        return rules;
    }

    private LicenceClassVersion map(ResultSet rs, int row) throws SQLException {
        Date to = rs.getDate("effective_to");
        LicenceClassVersion.Spec spec = new LicenceClassVersion.Spec(rs.getString("class_code"),
                rs.getString("name_nl"), rs.getString("name_en"), rs.getString("vehicle_type_nl"),
                rs.getString("vehicle_type_en"), rs.getBoolean("gearbox_recorded"),
                readRules(rs.getString("category_rules")), rs.getDate("effective_from").toLocalDate(), null);
        LicenceClassVersion.Decision submitted = new LicenceClassVersion.Decision(
                new Actor(rs.getString("submitted_by"), rs.getString("submitted_by_username")),
                Channel.valueOf(rs.getString("submitted_channel")), rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("submit_reason"));
        LicenceClassVersion.Decision decided = rs.getTimestamp("decided_at") == null ? null
                : new LicenceClassVersion.Decision(
                        new Actor(rs.getString("decided_by"), rs.getString("decided_by_username")),
                        Channel.valueOf(rs.getString("decided_channel")), rs.getTimestamp("decided_at").toInstant(),
                        rs.getString("decision_reason"));
        return LicenceClassVersion.restore(rs.getObject("id", UUID.class), rs.getInt("version_no"), spec, submitted,
                LicenceClassVersion.Status.valueOf(rs.getString("status")), decided,
                to == null ? null : to.toLocalDate());
    }
}

package gov.itms.rules.infrastructure;

import gov.itms.rules.application.RuleRepository;
import gov.itms.rules.domain.Actor;
import gov.itms.rules.domain.Channel;
import gov.itms.rules.domain.DecisionRecord;
import gov.itms.rules.domain.RuleVersion;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Rule versions and decisions in PostgreSQL; the database guards decisions and the append-only log (V1). */
class JdbcRuleRepository implements RuleRepository {

    private static final String SELECT = """
            select id, rule_set_code, version_no, name_nl, name_en, region, decision_table::text as decision_table,
                effective_from, effective_to, status, submitted_at, submitted_by, submitted_by_username,
                submitted_channel, submit_reason, decided_at, decided_by, decided_by_username, decided_channel,
                decision_reason
            from rule_version""";
    private static final String DECISION_SELECT = """
            select id, rule_set_code, version_id, version_no, region, decision_date, inputs::text as inputs,
                matched_rules, outputs::text as outputs, reference, actor_id, channel, correlation_id, decided_at
            from rule_decision""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcRuleRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public int nextVersionNo(String ruleSetCode) {
        return jdbc.sql("select coalesce(max(version_no), 0) + 1 from rule_version where rule_set_code = :c")
                .param("c", ruleSetCode).query(Integer.class).single();
    }

    @Override
    public void lockRuleSet(String ruleSetCode) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('rule:' || :c))").param("c", ruleSetCode).query().listOfRows();
    }

    @Override
    public void insert(RuleVersion v) {
        RuleVersion.Spec s = v.spec();
        jdbc.sql("""
                        insert into rule_version (id, rule_set_code, version_no, name_nl, name_en, region,
                            decision_table, effective_from, effective_to, status, submitted_at, submitted_by,
                            submitted_by_username, submitted_channel, submit_reason)
                        values (:id, :code, :no, :nameNl, :nameEn, :region, cast(:table as jsonb), :from, :to,
                            'PENDING', :at, :by, :byName, :channel, :reason)""")
                .param("id", v.id())
                .param("code", s.ruleSetCode())
                .param("no", v.versionNo())
                .param("nameNl", s.nameNl())
                .param("nameEn", s.nameEn())
                .param("region", s.region())
                .param("table", TableJson.write(json, s.table()))
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
    public Optional<RuleVersion> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<RuleVersion> findForUpdate(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(this::map).optional();
    }

    @Override
    public List<RuleVersion> byRuleSet(String ruleSetCode) {
        return jdbc.sql(SELECT + " where rule_set_code = :c order by version_no").param("c", ruleSetCode)
                .query(this::map).list();
    }

    @Override
    public void saveDecision(RuleVersion v) {
        RuleVersion.Decision d = v.decided();
        int updated = jdbc.sql("""
                        update rule_version set status = :status, decided_at = :at, decided_by = :by,
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
            throw new OptimisticLockingFailureException("rule version " + v.id() + " was decided concurrently");
        }
    }

    @Override
    public void saveEnd(RuleVersion v) {
        jdbc.sql("update rule_version set effective_to = :to where id = :id")
                .param("to", Date.valueOf(v.effectiveTo())).param("id", v.id()).update();
    }

    @Override
    public void insertDecision(DecisionRecord d) {
        jdbc.sql("""
                        insert into rule_decision (id, rule_set_code, version_id, version_no, region, decision_date,
                            inputs, matched_rules, outputs, reference, actor_id, channel, correlation_id, decided_at)
                        values (:id, :code, :version, :no, :region, :date, cast(:inputs as jsonb),
                            string_to_array(:matched, ',')::integer[], cast(:outputs as jsonb), :reference, :actor,
                            :channel, :correlation, :at)""")
                .param("id", d.id())
                .param("code", d.ruleSetCode())
                .param("version", d.versionId())
                .param("no", d.versionNo())
                .param("region", d.region())
                .param("date", Date.valueOf(d.date()))
                .param("inputs", json.writeValueAsString(d.inputs()))
                .param("matched", String.join(",", d.matchedRules().stream().map(String::valueOf).toList()))
                .param("outputs", json.writeValueAsString(d.outputs()))
                .param("reference", d.reference())
                .param("actor", d.actorId())
                .param("channel", d.channel().name())
                .param("correlation", d.correlationId())
                .param("at", Timestamp.from(d.at()))
                .update();
    }

    @Override
    public Optional<DecisionRecord> findDecision(UUID id) {
        return jdbc.sql(DECISION_SELECT + " where id = :id").param("id", id).query(this::mapDecision).optional();
    }

    @Override
    public List<DecisionRecord> decisions(String ruleSetCode, Instant since, int limit) {
        return jdbc.sql(DECISION_SELECT + " where rule_set_code = :c and decided_at >= :since"
                        + " order by decided_at desc limit :limit")
                .param("c", ruleSetCode).param("since", Timestamp.from(since)).param("limit", limit)
                .query(this::mapDecision).list();
    }

    private RuleVersion map(ResultSet rs, int row) throws SQLException {
        Date to = rs.getDate("effective_to");
        RuleVersion.Spec spec = new RuleVersion.Spec(rs.getString("rule_set_code"), rs.getString("name_nl"),
                rs.getString("name_en"), rs.getString("region"),
                TableJson.read(json, rs.getString("decision_table")), rs.getDate("effective_from").toLocalDate(),
                null);
        RuleVersion.Decision submitted = new RuleVersion.Decision(
                new Actor(rs.getString("submitted_by"), rs.getString("submitted_by_username")),
                Channel.valueOf(rs.getString("submitted_channel")), rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("submit_reason"));
        RuleVersion.Decision decided = rs.getTimestamp("decided_at") == null ? null : new RuleVersion.Decision(
                new Actor(rs.getString("decided_by"), rs.getString("decided_by_username")),
                Channel.valueOf(rs.getString("decided_channel")), rs.getTimestamp("decided_at").toInstant(),
                rs.getString("decision_reason"));
        return RuleVersion.restore(rs.getObject("id", UUID.class), rs.getInt("version_no"), spec, submitted,
                RuleVersion.Status.valueOf(rs.getString("status")), decided, to == null ? null : to.toLocalDate());
    }

    private DecisionRecord mapDecision(ResultSet rs, int row) throws SQLException {
        Array matched = rs.getArray("matched_rules");
        List<Integer> rules = matched == null ? List.of() : Arrays.asList((Integer[]) matched.getArray());
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (JsonNode o : json.readTree(rs.getString("outputs"))) {
            outputs.add(TableJson.values(o));
        }
        return new DecisionRecord(rs.getObject("id", UUID.class), rs.getString("rule_set_code"),
                rs.getObject("version_id", UUID.class), rs.getInt("version_no"), rs.getString("region"),
                rs.getDate("decision_date").toLocalDate(), TableJson.values(json.readTree(rs.getString("inputs"))),
                rules, outputs, rs.getString("reference"), rs.getString("actor_id"),
                Channel.valueOf(rs.getString("channel")), rs.getString("correlation_id"),
                rs.getTimestamp("decided_at").toInstant());
    }
}

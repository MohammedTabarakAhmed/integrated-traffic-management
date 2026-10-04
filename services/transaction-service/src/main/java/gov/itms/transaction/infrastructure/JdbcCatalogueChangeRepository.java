package gov.itms.transaction.infrastructure;

import gov.itms.transaction.application.CatalogueChangeRepository;
import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.AvailabilityRuleSpec;
import gov.itms.transaction.domain.BilingualName;
import gov.itms.transaction.domain.CatalogueChange;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.Codes;
import gov.itms.transaction.domain.DomainException;
import gov.itms.transaction.domain.ServiceSpec;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Stores the proposed content (service definition or rule) as submitted, in {@code payload} (jsonb). */
class JdbcCatalogueChangeRepository implements CatalogueChangeRepository {

    private static final String SELECT = """
            select id, change_type, status, service_code, payload::text as payload, base_version, rule_id, end_date,
                   reason, maker_id, maker_username, submitted_at, checker_id, checker_username, decided_at,
                   decision_reason
              from catalogue_change""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcCatalogueChangeRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(CatalogueChange c) {
        try {
            jdbc.sql("""
                            insert into catalogue_change (id, change_type, status, service_code, payload, base_version,
                                rule_id, end_date, reason, maker_id, maker_username, submitted_at)
                            values (:id, :type, :status, :code, cast(:payload as jsonb), :baseVersion, :ruleId, :endDate,
                                :reason, :maker, :makerName, :at)""")
                    .param("id", c.id())
                    .param("type", c.type().name())
                    .param("status", c.status().name())
                    .param("code", c.serviceCode())
                    .param("payload", json.writeValueAsString(payload(c)))
                    .param("baseVersion", c.baseVersion())
                    .param("ruleId", c.ruleId())
                    .param("endDate", c.endDate() == null ? null : Date.valueOf(c.endDate()))
                    .param("reason", c.reason())
                    .param("maker", c.maker().id())
                    .param("makerName", c.maker().username())
                    .param("at", Timestamp.from(c.submittedAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            // uq_catalogue_change_pending: another change to this service was submitted at the same moment.
            throw DomainException.conflict("CHANGE_ALREADY_PENDING",
                    "another change to service " + c.serviceCode() + " is waiting for approval", c.serviceCode());
        }
    }

    @Override
    public Optional<CatalogueChange> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<CatalogueChange> findForDecision(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public List<CatalogueChange> findByStatus(CatalogueChange.Status status) {
        return jdbc.sql(SELECT + " where (cast(:status as varchar) is null or status = :status)"
                        + " order by submitted_at desc, id")
                .param("status", status == null ? null : status.name())
                .query((rs, n) -> map(rs)).list();
    }

    @Override
    public boolean pendingExistsFor(String serviceCode) {
        return jdbc.sql("select count(*) from catalogue_change where service_code = :code and status = 'PENDING_APPROVAL'")
                .param("code", serviceCode).query(Long.class).single() > 0;
    }

    @Override
    public void saveDecision(CatalogueChange c) {
        jdbc.sql("""
                        update catalogue_change
                           set status = :status, checker_id = :checker, checker_username = :checkerName,
                               decided_at = :at, decision_reason = :why
                         where id = :id and status = 'PENDING_APPROVAL'""")
                .param("status", c.status().name())
                .param("checker", c.checker().id())
                .param("checkerName", c.checker().username())
                .param("at", Timestamp.from(c.decidedAt()))
                .param("why", c.decisionReason())
                .param("id", c.id())
                .update();
    }

    // ---------------------------------------------------------------- payload <-> domain

    private static Map<String, Object> payload(CatalogueChange c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c.service() != null) {
            ServiceSpec s = c.service();
            Map<String, Object> svc = new LinkedHashMap<>();
            svc.put("code", s.code());
            svc.put("nameNl", s.name().nl());
            svc.put("nameEn", s.name().en());
            svc.put("module", s.module());
            svc.put("customerTypes", s.customerTypes());
            svc.put("channels", s.channels().stream().map(Enum::name).toList());
            svc.put("requiredDocuments", s.requiredDocuments());
            svc.put("feeCode", s.feeCode());
            svc.put("ruleSetCode", s.ruleSetCode());
            svc.put("workflowCode", s.workflowCode());
            svc.put("sla", s.sla());
            svc.put("regions", s.regions());
            svc.put("anyRegion", s.anyRegion());
            svc.put("allowsConcurrentOpen", s.allowsConcurrentOpen());
            m.put("service", svc);
        }
        if (c.rule() != null) {
            AvailabilityRuleSpec r = c.rule();
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("region", r.region());
            rule.put("channel", r.allChannels() ? Codes.ALL : r.channel().name());
            rule.put("enabled", r.enabled());
            rule.put("effectiveFrom", r.effectiveFrom().toString());
            rule.put("effectiveTo", r.effectiveTo() == null ? null : r.effectiveTo().toString());
            m.put("rule", rule);
        }
        return m;
    }

    private CatalogueChange map(ResultSet rs) throws SQLException {
        JsonNode payload = json.readTree(rs.getString("payload"));
        String checker = rs.getString("checker_id");
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        Date endDate = rs.getDate("end_date");
        long baseVersion = rs.getLong("base_version");
        Long base = rs.wasNull() ? null : baseVersion;
        return new CatalogueChange(rs.getObject("id", UUID.class),
                CatalogueChange.Type.valueOf(rs.getString("change_type")), rs.getString("service_code"),
                service(payload.get("service")), base, rule(payload.get("rule")), rs.getObject("rule_id", UUID.class),
                endDate == null ? null : endDate.toLocalDate(), rs.getString("reason"),
                new Actor(rs.getString("maker_id"), rs.getString("maker_username")),
                rs.getTimestamp("submitted_at").toInstant(), CatalogueChange.Status.valueOf(rs.getString("status")),
                checker == null ? null : new Actor(checker, rs.getString("checker_username")),
                decidedAt == null ? null : decidedAt.toInstant(), rs.getString("decision_reason"));
    }

    private static ServiceSpec service(JsonNode s) {
        if (s == null || s.isNull()) {
            return null;
        }
        return new ServiceSpec(text(s, "code"), new BilingualName(text(s, "nameNl"), text(s, "nameEn")),
                text(s, "module"), texts(s.get("customerTypes")),
                texts(s.get("channels")).stream().map(Channel::valueOf).toList(), texts(s.get("requiredDocuments")),
                text(s, "feeCode"), text(s, "ruleSetCode"), text(s, "workflowCode"), text(s, "sla"),
                texts(s.get("regions")), s.get("anyRegion").asBoolean(), s.get("allowsConcurrentOpen").asBoolean());
    }

    private static AvailabilityRuleSpec rule(JsonNode r) {
        if (r == null || r.isNull()) {
            return null;
        }
        String channel = text(r, "channel");
        String to = text(r, "effectiveTo");
        return new AvailabilityRuleSpec(text(r, "region"), Codes.ALL.equals(channel) ? null : Channel.valueOf(channel),
                r.get("enabled").asBoolean(), LocalDate.parse(text(r, "effectiveFrom")),
                to == null ? null : LocalDate.parse(to));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asString()));
        return out;
    }
}

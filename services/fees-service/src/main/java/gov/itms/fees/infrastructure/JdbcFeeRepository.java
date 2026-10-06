package gov.itms.fees.infrastructure;

import gov.itms.fees.application.FeeRepository;
import gov.itms.fees.domain.Actor;
import gov.itms.fees.domain.Channel;
import gov.itms.fees.domain.FeeVersion;
import gov.itms.fees.domain.Quote;
import java.math.BigDecimal;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fee versions and quotes in PostgreSQL; the database guards decisions and freezes quotes (V1). */
class JdbcFeeRepository implements FeeRepository {

    private static final String SELECT = """
            select id, fee_code, version_no, service_code, name_nl, name_en, region, customer_category,
                subject_category, financial_item, beneficiary, method::text as method, currency, effective_from,
                effective_to, status, submitted_at, submitted_by, submitted_by_username, submitted_channel,
                submit_reason, decided_at, decided_by, decided_by_username, decided_channel, decision_reason
            from fee_version""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcFeeRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public int nextVersionNo(String feeCode) {
        return jdbc.sql("select coalesce(max(version_no), 0) + 1 from fee_version where fee_code = :c")
                .param("c", feeCode).query(Integer.class).single();
    }

    @Override
    public void lockFee(String feeCode) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('fee:' || :c))").param("c", feeCode).query().listOfRows();
    }

    @Override
    public void insert(FeeVersion v) {
        FeeVersion.Spec s = v.spec();
        jdbc.sql("""
                        insert into fee_version (id, fee_code, version_no, service_code, name_nl, name_en, region,
                            customer_category, subject_category, financial_item, beneficiary, method, currency,
                            effective_from, effective_to, status, submitted_at, submitted_by, submitted_by_username,
                            submitted_channel, submit_reason)
                        values (:id, :fee, :no, :service, :nameNl, :nameEn, :region, :customer, :subject, :item,
                            :beneficiary, cast(:method as jsonb), :currency, :from, :to, 'PENDING', :at, :by, :byName,
                            :channel, :reason)""")
                .param("id", v.id())
                .param("fee", s.feeCode())
                .param("no", v.versionNo())
                .param("service", s.serviceCode())
                .param("nameNl", s.nameNl())
                .param("nameEn", s.nameEn())
                .param("region", s.applicability().region())
                .param("customer", s.applicability().customerCategory())
                .param("subject", s.applicability().subjectCategory())
                .param("item", s.financialItem())
                .param("beneficiary", s.beneficiary())
                .param("method", FeeMethodJson.write(json, s.method()))
                .param("currency", v.currency())
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
    public Optional<FeeVersion> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<FeeVersion> findForUpdate(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(this::map).optional();
    }

    @Override
    public List<FeeVersion> byFee(String feeCode) {
        return jdbc.sql(SELECT + " where fee_code = :c order by version_no").param("c", feeCode).query(this::map)
                .list();
    }

    @Override
    public List<FeeVersion> approvedForService(String serviceCode, LocalDate date) {
        return jdbc.sql(SELECT + " where service_code = :s and status = 'APPROVED' and effective_from <= :d"
                        + " and (effective_to is null or effective_to >= :d)")
                .param("s", serviceCode).param("d", Date.valueOf(date)).query(this::map).list();
    }

    @Override
    public List<FeeVersion> byService(String serviceCode, FeeVersion.Status status) {
        return jdbc.sql(SELECT + " where service_code = :s and (cast(:status as varchar) is null or status = :status)"
                        + " order by fee_code, version_no desc")
                .param("s", serviceCode).param("status", status == null ? null : status.name())
                .query(this::map).list();
    }

    @Override
    public void saveDecision(FeeVersion v) {
        FeeVersion.Decision d = v.decided();
        int updated = jdbc.sql("""
                        update fee_version set status = :status, decided_at = :at, decided_by = :by,
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
            throw new OptimisticLockingFailureException("fee version " + v.id() + " was decided concurrently");
        }
    }

    @Override
    public void saveEnd(FeeVersion v) {
        jdbc.sql("update fee_version set effective_to = :to where id = :id")
                .param("to", Date.valueOf(v.effectiveTo())).param("id", v.id()).update();
    }

    @Override
    public void insertQuote(Quote q) {
        ArrayNode lines = json.createArrayNode();
        for (Quote.Line l : q.lines()) {
            ObjectNode n = lines.addObject();
            n.put("feeCode", l.feeCode());
            n.put("feeVersionId", l.feeVersionId().toString());
            n.put("versionNo", l.versionNo());
            n.put("nameNl", l.nameNl());
            n.put("nameEn", l.nameEn());
            n.put("financialItem", l.financialItem());
            n.put("beneficiary", l.beneficiary());
            n.put("amount", l.amount());
        }
        ObjectNode quantities = json.createObjectNode();
        q.request().quantities().forEach((k, v) -> quantities.put(k, v.toPlainString()));
        jdbc.sql("""
                        insert into fee_quote (id, service_code, region, customer_category, subject_category,
                            quantities, quote_date, lines, total, currency, created_at, expires_at, created_by)
                        values (:id, :service, :region, :customer, :subject, cast(:quantities as jsonb), :date,
                            cast(:lines as jsonb), :total, :currency, :at, :expires, :by)""")
                .param("id", q.id())
                .param("service", q.request().serviceCode())
                .param("region", q.request().region())
                .param("customer", q.request().customerCategory())
                .param("subject", q.request().subjectCategory())
                .param("quantities", json.writeValueAsString(quantities))
                .param("date", Date.valueOf(q.request().date()))
                .param("lines", json.writeValueAsString(lines))
                .param("total", q.total())
                .param("currency", q.currency())
                .param("at", Timestamp.from(q.createdAt()))
                .param("expires", Timestamp.from(q.expiresAt()))
                .param("by", q.createdBy())
                .update();
    }

    @Override
    public Optional<Quote> findQuote(UUID id) {
        return jdbc.sql("""
                        select id, service_code, region, customer_category, subject_category, quantities::text as q,
                            quote_date, lines::text as lines, total, currency, created_at, expires_at, created_by
                        from fee_quote where id = :id""")
                .param("id", id)
                .query((rs, n) -> {
                    Map<String, BigDecimal> quantities = new LinkedHashMap<>();
                    json.readTree(rs.getString("q")).properties()
                            .forEach(e -> quantities.put(e.getKey(), new BigDecimal(e.getValue().asString())));
                    List<Quote.Line> lines = new ArrayList<>();
                    for (JsonNode l : json.readTree(rs.getString("lines"))) {
                        lines.add(new Quote.Line(l.get("feeCode").asString(),
                                UUID.fromString(l.get("feeVersionId").asString()), l.get("versionNo").asInt(),
                                l.get("nameNl").asString(), l.get("nameEn").asString(),
                                l.get("financialItem").asString(), l.get("beneficiary").asString(),
                                l.get("amount").asLong()));
                    }
                    Quote.Request request = new Quote.Request(rs.getString("service_code"), rs.getString("region"),
                            rs.getString("customer_category"), rs.getString("subject_category"), quantities,
                            rs.getDate("quote_date").toLocalDate());
                    return new Quote(rs.getObject("id", UUID.class), request, lines, rs.getLong("total"),
                            rs.getString("currency"), rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant(), rs.getString("created_by"));
                })
                .optional();
    }

    private FeeVersion map(ResultSet rs, int row) throws SQLException {
        Date to = rs.getDate("effective_to");
        FeeVersion.Spec spec = new FeeVersion.Spec(rs.getString("fee_code"), rs.getString("service_code"),
                rs.getString("name_nl"), rs.getString("name_en"),
                new FeeVersion.Applicability(rs.getString("region"), rs.getString("customer_category"),
                        rs.getString("subject_category")),
                rs.getString("financial_item"), rs.getString("beneficiary"),
                FeeMethodJson.read(json, rs.getString("method")), rs.getDate("effective_from").toLocalDate(), null);
        FeeVersion.Decision submitted = new FeeVersion.Decision(
                new Actor(rs.getString("submitted_by"), rs.getString("submitted_by_username")),
                Channel.valueOf(rs.getString("submitted_channel")), rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("submit_reason"));
        FeeVersion.Decision decided = rs.getTimestamp("decided_at") == null ? null : new FeeVersion.Decision(
                new Actor(rs.getString("decided_by"), rs.getString("decided_by_username")),
                Channel.valueOf(rs.getString("decided_channel")), rs.getTimestamp("decided_at").toInstant(),
                rs.getString("decision_reason"));
        return FeeVersion.restore(rs.getObject("id", UUID.class), rs.getInt("version_no"), spec,
                rs.getString("currency"), submitted, FeeVersion.Status.valueOf(rs.getString("status")), decided,
                to == null ? null : to.toLocalDate());
    }
}

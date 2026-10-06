package gov.itms.audit.infrastructure;

import gov.itms.audit.application.AuditStore;
import gov.itms.audit.domain.AuditRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The audit trail in PostgreSQL; the guard trigger makes it append-only (V1). */
class JdbcAuditStore implements AuditStore {

    private static final String SELECT = """
            select seq, audit_id, occurred_at, service, action, entity_type, entity_id, actor_id, actor_username,
                actor_roles::text as actor_roles, channel, ip_address, correlation_id, old_value, new_value, reason,
                received_at, prev_hash, hash
            from audit_record""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcAuditStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void lockChain() {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('audit-chain'))").query().listOfRows();
    }

    @Override
    public boolean exists(UUID auditId) {
        return jdbc.sql("select exists (select 1 from audit_record where audit_id = :id)").param("id", auditId)
                .query(Boolean.class).single();
    }

    @Override
    public Optional<AuditRecord> last() {
        return jdbc.sql(SELECT + " order by seq desc limit 1").query(this::map).optional();
    }

    @Override
    public Optional<AuditRecord> first() {
        return jdbc.sql(SELECT + " order by seq limit 1").query(this::map).optional();
    }

    @Override
    public void append(AuditRecord r) {
        jdbc.sql("""
                        insert into audit_record (seq, audit_id, occurred_at, service, action, entity_type, entity_id,
                            actor_id, actor_username, actor_roles, channel, ip_address, correlation_id, old_value,
                            new_value, reason, received_at, prev_hash, hash)
                        values (:seq, :id, :at, :service, :action, :entityType, :entityId, :actorId, :actorName,
                            cast(:roles as jsonb), :channel, :ip, :correlation, :old, :new, :reason, :received,
                            :prev, :hash)""")
                .param("seq", r.seq())
                .param("id", r.auditId())
                .param("at", Timestamp.from(r.occurredAt()))
                .param("service", r.service())
                .param("action", r.action())
                .param("entityType", r.entityType())
                .param("entityId", r.entityId())
                .param("actorId", r.actorId())
                .param("actorName", r.actorUsername())
                .param("roles", json.writeValueAsString(r.actorRoles()))
                .param("channel", r.channel())
                .param("ip", r.ipAddress())
                .param("correlation", r.correlationId())
                .param("old", r.oldValue())
                .param("new", r.newValue())
                .param("reason", r.reason())
                .param("received", Timestamp.from(r.receivedAt()))
                .param("prev", r.prevHash())
                .param("hash", r.hash())
                .update();
    }

    @Override
    public List<AuditRecord> search(Query q) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where true");
        List<Object[]> params = new ArrayList<>();
        add(sql, params, q.entityType(), " and entity_type = :entityType", "entityType");
        add(sql, params, q.entityId(), " and entity_id = :entityId", "entityId");
        add(sql, params, q.actorId(), " and actor_id = :actorId", "actorId");
        add(sql, params, q.service(), " and service = :service", "service");
        add(sql, params, q.action(), " and action = :action", "action");
        add(sql, params, q.from() == null ? null : Timestamp.from(q.from()), " and occurred_at >= :from", "from");
        add(sql, params, q.to() == null ? null : Timestamp.from(q.to()), " and occurred_at < :to", "to");
        add(sql, params, q.afterSeq(), " and seq > :after", "after");
        sql.append(" order by seq limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", q.limit());
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query(this::map).list();
    }

    private static void add(StringBuilder sql, List<Object[]> params, Object value, String clause, String name) {
        if (value != null) {
            sql.append(clause);
            params.add(new Object[] {name, value});
        }
    }

    @Override
    public List<AuditRecord> chain(long fromSeq, int limit) {
        return jdbc.sql(SELECT + " where seq >= :from order by seq limit :limit").param("from", fromSeq)
                .param("limit", limit).query(this::map).list();
    }

    @Override
    public int purge(Instant before, int limit) {
        jdbc.sql("select set_config('itms.audit_purge', 'on', true), "
                        + "set_config('itms.audit_purge_before', :before, true)")
                .param("before", before.toString()).query().listOfRows();
        return jdbc.sql("""
                        delete from audit_record where seq in (select seq from audit_record
                            where occurred_at < :before order by seq limit :limit)""")
                .param("before", Timestamp.from(before)).param("limit", limit).update();
    }

    private AuditRecord map(ResultSet rs, int row) throws SQLException {
        List<String> roles = new ArrayList<>();
        JsonNode r = json.readTree(rs.getString("actor_roles"));
        r.forEach(n -> roles.add(n.asString()));
        return new AuditRecord(rs.getLong("seq"), rs.getObject("audit_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("service"), rs.getString("action"),
                rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("actor_id"),
                rs.getString("actor_username"), roles, rs.getString("channel"), rs.getString("ip_address"),
                rs.getString("correlation_id"), rs.getString("old_value"), rs.getString("new_value"),
                rs.getString("reason"), rs.getTimestamp("received_at").toInstant(), rs.getString("prev_hash"),
                rs.getString("hash"));
    }
}

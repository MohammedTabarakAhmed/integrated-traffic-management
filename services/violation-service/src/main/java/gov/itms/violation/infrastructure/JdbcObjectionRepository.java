package gov.itms.violation.infrastructure;

import gov.itms.violation.application.Ports;
import gov.itms.violation.domain.Objection;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code violation_objection} (V4): one objection per violation, decided once. */
class JdbcObjectionRepository implements Ports.Objections {

    private final JdbcClient jdbc;

    JdbcObjectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(Objection o) {
        return jdbc.sql("""
                insert into violation_objection (id, violation_id, violation_number, submitted_by, submitted_at,
                    reason_code, explanation, evidence_ids, status)
                values (:id, :vid, :number, :by, :at, :reason, :explanation, :evidence, :status)
                on conflict do nothing
                """).param("id", o.id()).param("vid", o.violationId()).param("number", o.violationNumber())
                .param("by", o.submittedBy()).param("at", ts(o.submittedAt())).param("reason", o.reasonCode())
                .param("explanation", o.explanation()).param("evidence", o.evidenceIds().toArray(String[]::new))
                .param("status", o.status().name()).update() > 0;
    }

    @Override
    public List<Ports.Objections.Queued> pendingLocal(int limit, Instant afterSubmittedAt, UUID afterId) {
        return jdbc.sql("""
                select o.*, v.region from violation_objection o join violation v on v.id = o.violation_id
                where o.status = 'PENDING' and not exists (
                    select 1 from violation_crossborder x where x.violation_id = o.violation_id
                    and x.direction = 'INBOUND')
                  and (cast(:after as timestamptz) is null
                       or (o.submitted_at, o.id) > (cast(:after as timestamptz), cast(:afterId as uuid)))
                order by o.submitted_at, o.id limit :n
                """).param("after", ts(afterSubmittedAt)).param("afterId", afterId).param("n", limit)
                .query((rs, row) -> new Ports.Objections.Queued(map(rs), rs.getString("region"))).list();
    }

    @Override
    public Optional<Objection> lock(UUID id) {
        return jdbc.sql("select * from violation_objection where id = :id for update").param("id", id)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public void saveDecision(Objection d) {
        jdbc.sql("update violation_objection set status = :status, decided_by = :by, decided_at = :at,"
                        + " decision = :decision, decision_reason = :reason, reduced_fine_minor = :reduced"
                        + " where id = :id")
                .param("status", d.status().name()).param("by", d.decidedBy()).param("at", ts(d.decidedAt()))
                .param("decision", d.decision().name()).param("reason", d.decisionReason())
                .param("reduced", d.reducedFineMinor()).param("id", d.id())
                .update();
    }

    @Override
    public boolean decidePending(String number, Objection.Decision decision, String decidedBy, Instant at,
                                 String reason) {
        if (decision == Objection.Decision.REDUCE) {
            throw new IllegalArgumentException("a partner decision cancels or stands; it never reduces here");
        }
        String status = Objection.statusOf(decision).name();
        return jdbc.sql("""
                update violation_objection set status = :status, decided_by = :by, decided_at = :at,
                    decision = :decision, decision_reason = :reason
                where violation_number = :number and status = 'PENDING'
                """).param("status", status).param("by", decidedBy).param("at", ts(at))
                .param("decision", decision.name()).param("reason", reason).param("number", number).update() > 0;
    }

    private static Objection map(ResultSet rs) throws SQLException {
        Array array = rs.getArray("evidence_ids");
        String[] values = array == null ? new String[0] : (String[]) array.getArray();
        String decision = rs.getString("decision");
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new Objection(rs.getObject("id", UUID.class), rs.getObject("violation_id", UUID.class),
                rs.getString("violation_number"), rs.getString("submitted_by"),
                rs.getTimestamp("submitted_at").toInstant(), rs.getString("reason_code"), rs.getString("explanation"),
                List.of(values), Objection.Status.valueOf(rs.getString("status")), rs.getString("decided_by"),
                decidedAt == null ? null : decidedAt.toInstant(),
                decision == null ? null : Objection.Decision.valueOf(decision), rs.getString("decision_reason"),
                rs.getObject("reduced_fine_minor", Long.class));
    }

    private static java.sql.Timestamp ts(java.time.Instant at) {
        return at == null ? null : java.sql.Timestamp.from(at);
    }
}

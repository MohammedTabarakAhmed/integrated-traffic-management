package gov.itms.violation.infrastructure;

import gov.itms.violation.application.Ports;
import gov.itms.violation.domain.PreservationOrder;
import gov.itms.violation.domain.ViolationPledge;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code violation_pledge} and {@code violation_preservation_order} (V10): append-only records (VF-046, VF-047). */
class JdbcCaseRecords implements Ports.Pledges, Ports.PreservationOrders {

    private final JdbcClient jdbc;

    JdbcCaseRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(ViolationPledge p) {
        return jdbc.sql("""
                insert into violation_pledge (id, violation_id, violation_number, signed_by, signed_at, note,
                    document_ids, recorded_by, recorded_at)
                values (:id, :vid, :number, :by, :signedAt, :note, :documents, :recorderBy, :recordedAt)
                on conflict do nothing
                """).param("id", p.id()).param("vid", p.violationId()).param("number", p.violationNumber())
                .param("by", p.signedBy()).param("signedAt", ts(p.signedAt())).param("note", p.note())
                .param("documents", p.documentIds().toArray(String[]::new)).param("recorderBy", p.recordedBy())
                .param("recordedAt", ts(p.recordedAt())).update() > 0;
    }

    @Override
    public List<ViolationPledge> pledgesOf(String violationNumber) {
        return jdbc.sql("""
                select * from violation_pledge where violation_number = :number order by recorded_at desc, id
                """).param("number", violationNumber).query((rs, row) -> mapPledge(rs)).list();
    }

    @Override
    public boolean insert(PreservationOrder o) {
        return jdbc.sql("""
                insert into violation_preservation_order (id, violation_id, violation_number, reference, source,
                    start_at, end_at, note, recorded_by, recorded_at)
                values (:id, :vid, :number, :reference, :source, :startAt, :endAt, :note, :recorderBy, :recordedAt)
                on conflict do nothing
                """).param("id", o.id()).param("vid", o.violationId()).param("number", o.violationNumber())
                .param("reference", o.reference()).param("source", o.source()).param("startAt", ts(o.startAt()))
                .param("endAt", ts(o.endAt())).param("note", o.note()).param("recorderBy", o.recordedBy())
                .param("recordedAt", ts(o.recordedAt())).update() > 0;
    }

    @Override
    public List<PreservationOrder> ordersOf(String violationNumber) {
        return jdbc.sql("""
                select * from violation_preservation_order where violation_number = :number
                order by recorded_at desc, id
                """).param("number", violationNumber).query((rs, row) -> mapOrder(rs)).list();
    }

    private static ViolationPledge mapPledge(ResultSet rs) throws SQLException {
        Array array = rs.getArray("document_ids");
        String[] values = array == null ? new String[0] : (String[]) array.getArray();
        return new ViolationPledge(rs.getObject("id", UUID.class), rs.getObject("violation_id", UUID.class),
                rs.getString("violation_number"), rs.getString("signed_by"),
                rs.getTimestamp("signed_at").toInstant(), rs.getString("note"), List.of(values),
                rs.getString("recorded_by"), rs.getTimestamp("recorded_at").toInstant());
    }

    private static PreservationOrder mapOrder(ResultSet rs) throws SQLException {
        Timestamp endAt = rs.getTimestamp("end_at");
        return new PreservationOrder(rs.getObject("id", UUID.class), rs.getObject("violation_id", UUID.class),
                rs.getString("violation_number"), rs.getString("reference"), rs.getString("source"),
                rs.getTimestamp("start_at").toInstant(), endAt == null ? null : endAt.toInstant(),
                rs.getString("note"), rs.getString("recorded_by"), rs.getTimestamp("recorded_at").toInstant());
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }
}

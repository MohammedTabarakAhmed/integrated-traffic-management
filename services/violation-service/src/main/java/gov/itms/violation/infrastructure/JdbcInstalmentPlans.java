package gov.itms.violation.infrastructure;

import gov.itms.violation.application.Ports;
import gov.itms.violation.domain.InstalmentPlan;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code instalment_plan}, {@code instalment_plan_violation} and {@code instalment} (V9; VF-071, VF-073). */
class JdbcInstalmentPlans implements Ports.InstalmentPlans {

    private final JdbcClient jdbc;

    JdbcInstalmentPlans(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long nextPlanSequence() {
        return jdbc.sql("select nextval('instalment_plan_seq')").query(Long.class).single();
    }

    @Override
    public boolean insert(InstalmentPlan p) {
        jdbc.sql("""
                insert into instalment_plan (id, number, file_number, region, currency, total_minor, months,
                    grace_days, status, created_by, created_at)
                values (:id, :number, :file, :region, :currency, :total, :months, :grace, :status, :by, :at)
                """).param("id", p.id()).param("number", p.number()).param("file", p.fileNumber())
                .param("region", p.region()).param("currency", p.currency()).param("total", p.totalMinor())
                .param("months", p.months()).param("grace", p.graceDays()).param("status", p.status().name())
                .param("by", p.createdBy()).param("at", Timestamp.from(p.createdAt())).update();
        try {
            for (String number : p.violationNumbers()) {
                jdbc.sql("""
                        insert into instalment_plan_violation (plan_id, violation_id, violation_number)
                        select :plan, id, number from violation where number = :number
                        """).param("plan", p.id()).param("number", number).update();
            }
        } catch (DuplicateKeyException e) {
            return false;
        }
        for (InstalmentPlan.Instalment i : p.instalments()) {
            jdbc.sql("""
                    insert into instalment (plan_id, seq, due_date, amount_minor, payable_id)
                    values (:plan, :seq, :due, :amount, :payable)
                    """).param("plan", p.id()).param("seq", i.seq()).param("due", i.dueDate())
                    .param("amount", i.amountMinor()).param("payable", i.payableId()).update();
        }
        return true;
    }

    @Override
    public Optional<InstalmentPlan> find(String number) {
        return jdbc.sql("select * from instalment_plan where number = :n").param("n", number)
                .query((rs, row) -> head(rs)).optional().map(this::complete);
    }

    @Override
    public Optional<InstalmentPlan> lock(String number) {
        return jdbc.sql("select * from instalment_plan where number = :n for update").param("n", number)
                .query((rs, row) -> head(rs)).optional().map(this::complete);
    }

    @Override
    public List<InstalmentPlan> byFile(String fileNumber, int limit) {
        return jdbc.sql("select * from instalment_plan where file_number = :f order by created_at desc limit :l")
                .param("f", fileNumber).param("l", limit).query((rs, row) -> head(rs)).list().stream()
                .map(this::complete).toList();
    }

    @Override
    public List<String> activeWithLapsedInstalment(LocalDate today, int limit) {
        return jdbc.sql("""
                select p.number from instalment_plan p
                 where p.status = 'ACTIVE' and exists (
                       select 1 from instalment i where i.plan_id = p.id and i.paid_at is null
                          and i.due_date + p.grace_days < :today)
                 order by p.created_at limit :l
                """).param("today", today).param("l", limit).query(String.class).list();
    }

    @Override
    public void instalmentPaid(UUID planId, int seq, Instant at) {
        jdbc.sql("update instalment set paid_at = :at where plan_id = :p and seq = :s and paid_at is null")
                .param("at", Timestamp.from(at)).param("p", planId).param("s", seq).update();
    }

    @Override
    public void instalmentReopened(UUID planId, int seq) {
        jdbc.sql("update instalment set paid_at = null where plan_id = :p and seq = :s")
                .param("p", planId).param("s", seq).update();
    }

    @Override
    public void status(UUID planId, InstalmentPlan.Status status, Instant closedAt) {
        jdbc.sql("update instalment_plan set status = :s, closed_at = :c where id = :p")
                .param("s", status.name()).param("c", closedAt == null ? null : Timestamp.from(closedAt))
                .param("p", planId).update();
    }

    /** The plan row without its violations and instalments. */
    private static InstalmentPlan head(ResultSet rs) throws SQLException {
        Timestamp closed = rs.getTimestamp("closed_at");
        return new InstalmentPlan(rs.getObject("id", UUID.class), rs.getString("number"),
                rs.getString("file_number"), rs.getString("region"), rs.getString("currency"),
                rs.getLong("total_minor"), rs.getInt("months"), rs.getInt("grace_days"),
                InstalmentPlan.Status.valueOf(rs.getString("status")), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), closed == null ? null : closed.toInstant(), List.of(),
                List.of());
    }

    private InstalmentPlan complete(InstalmentPlan p) {
        List<String> violations = jdbc.sql("""
                select violation_number from instalment_plan_violation where plan_id = :p order by violation_number
                """).param("p", p.id()).query(String.class).list();
        List<InstalmentPlan.Instalment> instalments = jdbc.sql("""
                select * from instalment where plan_id = :p order by seq
                """).param("p", p.id()).query((rs, row) -> {
                    Timestamp paid = rs.getTimestamp("paid_at");
                    return new InstalmentPlan.Instalment(rs.getInt("seq"), rs.getObject("due_date", LocalDate.class),
                            rs.getLong("amount_minor"), rs.getObject("payable_id", UUID.class), paid != null,
                            paid == null ? null : paid.toInstant());
                }).list();
        return new InstalmentPlan(p.id(), p.number(), p.fileNumber(), p.region(), p.currency(), p.totalMinor(),
                p.months(), p.graceDays(), p.status(), p.createdBy(), p.createdAt(), p.closedAt(), violations,
                instalments);
    }
}

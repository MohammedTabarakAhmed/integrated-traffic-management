package gov.itms.iam.infrastructure;

import gov.itms.iam.application.ApprovalLimitRepository;
import gov.itms.iam.domain.ApprovalLimit;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Approval limits per role (CC-003). The table's trigger keeps the history (DAT-002). */
class JdbcApprovalLimitRepository implements ApprovalLimitRepository {

    private final JdbcClient jdbc;

    JdbcApprovalLimitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ApprovalLimit> forRole(String roleCode) {
        return jdbc.sql("""
                        select limit_type, max_amount_minor, currency from approval_limit
                         where role_code = :role order by limit_type, currency""")
                .param("role", roleCode)
                .query((rs, n) -> new ApprovalLimit(rs.getString("limit_type"), rs.getLong("max_amount_minor"),
                        rs.getString("currency")))
                .list();
    }

    /** Touches only rows that change, so the history shows exactly what was removed, changed or added. */
    @Override
    public void replace(String roleCode, List<ApprovalLimit> limits, String actorId, Instant at) {
        Map<String, ApprovalLimit> current = new LinkedHashMap<>();
        forRole(roleCode).forEach(l -> current.put(key(l), l));
        Map<String, ApprovalLimit> wanted = new LinkedHashMap<>();
        limits.forEach(l -> wanted.put(key(l), l));

        for (ApprovalLimit old : current.values()) {
            if (!wanted.containsKey(key(old))) {
                jdbc.sql("""
                                delete from approval_limit
                                 where role_code = :role and limit_type = :type and currency = :currency""")
                        .param("role", roleCode).param("type", old.limitType()).param("currency", old.currency())
                        .update();
            }
        }
        for (ApprovalLimit l : wanted.values()) {
            ApprovalLimit old = current.get(key(l));
            if (old == null) {
                jdbc.sql("""
                                insert into approval_limit (role_code, limit_type, currency, max_amount_minor,
                                    changed_at, changed_by)
                                values (:role, :type, :currency, :amount, :at, :actor)""")
                        .param("role", roleCode).param("type", l.limitType()).param("currency", l.currency())
                        .param("amount", l.maxAmountMinor()).param("at", Timestamp.from(at)).param("actor", actorId)
                        .update();
            } else if (old.maxAmountMinor() != l.maxAmountMinor()) {
                jdbc.sql("""
                                update approval_limit
                                   set max_amount_minor = :amount, changed_at = :at, changed_by = :actor
                                 where role_code = :role and limit_type = :type and currency = :currency""")
                        .param("amount", l.maxAmountMinor()).param("at", Timestamp.from(at)).param("actor", actorId)
                        .param("role", roleCode).param("type", l.limitType()).param("currency", l.currency())
                        .update();
            }
        }
    }

    private static String key(ApprovalLimit l) {
        return l.limitType() + "/" + l.currency();
    }
}

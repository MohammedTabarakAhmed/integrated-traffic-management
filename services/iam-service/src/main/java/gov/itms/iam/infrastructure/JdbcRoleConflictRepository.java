package gov.itms.iam.infrastructure;

import gov.itms.iam.application.RoleConflictRepository;
import gov.itms.iam.domain.Actor;
import gov.itms.iam.domain.RoleConflict;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcRoleConflictRepository implements RoleConflictRepository {

    private static final String SELECT = """
            select id, role_a, role_b, reason, status, created_by, created_by_username, created_at, retired_by,
                   retired_by_username, retired_at, retirement_reason
              from role_conflict""";

    private final JdbcClient jdbc;

    JdbcRoleConflictRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(RoleConflict c) {
        jdbc.sql("""
                        insert into role_conflict (id, role_a, role_b, reason, status, created_by, created_by_username,
                            created_at)
                        values (:id, :roleA, :roleB, :reason, :status, :by, :byName, :at)""")
                .param("id", c.id())
                .param("roleA", c.roleA())
                .param("roleB", c.roleB())
                .param("reason", c.reason())
                .param("status", c.status().name())
                .param("by", c.createdBy().id())
                .param("byName", c.createdBy().username())
                .param("at", Timestamp.from(c.createdAt()))
                .update();
    }

    @Override
    public Optional<RoleConflict> findForUpdate(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public void saveRetirement(RoleConflict c) {
        jdbc.sql("""
                        update role_conflict
                           set status = :status, retired_by = :by, retired_by_username = :byName, retired_at = :at,
                               retirement_reason = :reason
                         where id = :id and status = 'ACTIVE'""")
                .param("status", c.status().name())
                .param("by", c.retiredBy().id())
                .param("byName", c.retiredBy().username())
                .param("at", Timestamp.from(c.retiredAt()))
                .param("reason", c.retirementReason())
                .param("id", c.id())
                .update();
    }

    @Override
    public List<RoleConflict> search(RoleConflict.Status status) {
        return jdbc.sql(SELECT + " " + """
                         where (cast(:status as varchar) is null or status = :status)
                         order by created_at desc, id""")
                .param("status", status == null ? null : status.name())
                .query((rs, n) -> map(rs)).list();
    }

    private static RoleConflict map(ResultSet rs) throws SQLException {
        String retiredBy = rs.getString("retired_by");
        Timestamp retiredAt = rs.getTimestamp("retired_at");
        return new RoleConflict(rs.getObject("id", UUID.class), rs.getString("role_a"), rs.getString("role_b"),
                rs.getString("reason"), new Actor(rs.getString("created_by"), rs.getString("created_by_username")),
                rs.getTimestamp("created_at").toInstant(), RoleConflict.Status.valueOf(rs.getString("status")),
                retiredBy == null ? null : new Actor(retiredBy, rs.getString("retired_by_username")),
                retiredAt == null ? null : retiredAt.toInstant(), rs.getString("retirement_reason"));
    }
}

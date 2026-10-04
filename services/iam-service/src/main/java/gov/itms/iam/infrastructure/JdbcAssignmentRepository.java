package gov.itms.iam.infrastructure;

import gov.itms.iam.application.AssignmentRepository;
import gov.itms.iam.domain.Actor;
import gov.itms.iam.domain.AssignmentScopes;
import gov.itms.iam.domain.RoleAssignment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcAssignmentRepository implements AssignmentRepository {

    private static final String SELECT = """
            select id, subject_id, subject_username, role_code, regions, centres, modules, valid_from, valid_to,
                   status, reason, assigned_by, assigned_by_username, assigned_at, revoked_by, revoked_by_username,
                   revoked_at, revocation_reason
              from role_assignment""";

    private final JdbcClient jdbc;

    JdbcAssignmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(RoleAssignment a) {
        jdbc.sql("""
                        insert into role_assignment (id, subject_id, subject_username, role_code, regions, centres,
                            modules, valid_from, valid_to, status, reason, assigned_by, assigned_by_username,
                            assigned_at)
                        values (:id, :subject, :subjectName, :role, string_to_array(:regions, ','),
                            string_to_array(:centres, ','), string_to_array(:modules, ','), :from, :to, :status,
                            :reason, :by, :byName, :at)""")
                .param("id", a.id())
                .param("subject", a.subjectId())
                .param("subjectName", a.subjectUsername())
                .param("role", a.roleCode())
                .param("regions", SqlArrays.join(a.scopes().regions()))
                .param("centres", SqlArrays.join(a.scopes().centres()))
                .param("modules", SqlArrays.join(a.scopes().modules()))
                .param("from", Timestamp.from(a.validFrom()))
                .param("to", a.validTo() == null ? null : Timestamp.from(a.validTo()))
                .param("status", a.status().name())
                .param("reason", a.reason())
                .param("by", a.assignedBy().id())
                .param("byName", a.assignedBy().username())
                .param("at", Timestamp.from(a.assignedAt()))
                .update();
    }

    @Override
    public Optional<RoleAssignment> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<RoleAssignment> findForUpdate(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public void saveRevocation(RoleAssignment a) {
        jdbc.sql("""
                        update role_assignment
                           set status = :status, revoked_by = :by, revoked_by_username = :byName, revoked_at = :at,
                               revocation_reason = :reason
                         where id = :id and status = 'ACTIVE'""")
                .param("status", a.status().name())
                .param("by", a.revokedBy().id())
                .param("byName", a.revokedBy().username())
                .param("at", Timestamp.from(a.revokedAt()))
                .param("reason", a.revocationReason())
                .param("id", a.id())
                .update();
    }

    @Override
    public List<RoleAssignment> search(String subjectId, String roleCode, RoleAssignment.Status status) {
        return jdbc.sql(SELECT + " " + """
                         where (cast(:subject as varchar) is null or subject_id = :subject)
                           and (cast(:role as varchar) is null or role_code = :role)
                           and (cast(:status as varchar) is null or status = :status)
                         order by assigned_at desc, id""")
                .param("subject", subjectId)
                .param("role", roleCode)
                .param("status", status == null ? null : status.name())
                .query((rs, n) -> map(rs)).list();
    }

    private static RoleAssignment map(ResultSet rs) throws SQLException {
        String revokedBy = rs.getString("revoked_by");
        return new RoleAssignment(
                rs.getObject("id", UUID.class), rs.getString("subject_id"), rs.getString("subject_username"),
                rs.getString("role_code"),
                AssignmentScopes.of(SqlArrays.read(rs, "regions"), SqlArrays.read(rs, "centres"),
                        SqlArrays.read(rs, "modules")),
                instant(rs, "valid_from"), instant(rs, "valid_to"), rs.getString("reason"),
                new Actor(rs.getString("assigned_by"), rs.getString("assigned_by_username")),
                instant(rs, "assigned_at"), RoleAssignment.Status.valueOf(rs.getString("status")),
                revokedBy == null ? null : new Actor(revokedBy, rs.getString("revoked_by_username")),
                instant(rs, "revoked_at"), rs.getString("revocation_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}

package gov.itms.iam.infrastructure;

import gov.itms.iam.application.RoleRepository;
import gov.itms.iam.domain.BilingualName;
import gov.itms.iam.domain.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcRoleRepository implements RoleRepository {

    private static final String SELECT = """
            select code, name_nl, name_en, description, active, permissions, version from role""";

    private final JdbcClient jdbc;

    JdbcRoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Role> find(String code) {
        return jdbc.sql(SELECT + " where code = :code").param("code", code).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public List<Role> findAll() {
        return jdbc.sql(SELECT + " order by code").query((rs, n) -> map(rs)).list();
    }

    @Override
    public void insert(Role role, String actorId, Instant at) {
        jdbc.sql("""
                        insert into role (code, name_nl, name_en, description, active, permissions, version,
                            created_at, created_by, updated_at, updated_by)
                        values (:code, :nl, :en, :description, :active, string_to_array(:permissions, ','), :version,
                            :at, :actor, :at, :actor)""")
                .param("code", role.code())
                .param("nl", role.name().nl())
                .param("en", role.name().en())
                .param("description", role.description())
                .param("active", role.active())
                .param("permissions", SqlArrays.join(role.permissions()))
                .param("version", role.version())
                .param("at", Timestamp.from(at))
                .param("actor", actorId)
                .update();
    }

    @Override
    public boolean update(Role role, long expectedVersion, String actorId, Instant at) {
        return jdbc.sql("""
                        update role
                           set name_nl = :nl, name_en = :en, description = :description, active = :active,
                               permissions = string_to_array(:permissions, ','), version = :version,
                               updated_at = :at, updated_by = :actor
                         where code = :code and version = :expected""")
                .param("nl", role.name().nl())
                .param("en", role.name().en())
                .param("description", role.description())
                .param("active", role.active())
                .param("permissions", SqlArrays.join(role.permissions()))
                .param("version", role.version())
                .param("at", Timestamp.from(at))
                .param("actor", actorId)
                .param("code", role.code())
                .param("expected", expectedVersion)
                .update() == 1;
    }

    @Override
    public List<RoleVersion> history(String code) {
        return jdbc.sql("""
                        select role_code as code, name_nl, name_en, description, active, permissions, version,
                               valid_from, valid_to, changed_by
                          from role_history where role_code = :code order by version""")
                .param("code", code)
                .query((rs, n) -> {
                    Timestamp validTo = rs.getTimestamp("valid_to");
                    return new RoleVersion(map(rs), rs.getTimestamp("valid_from").toInstant(),
                            validTo == null ? null : validTo.toInstant(), rs.getString("changed_by"));
                })
                .list();
    }

    private static Role map(ResultSet rs) throws SQLException {
        return new Role(rs.getString("code"), new BilingualName(rs.getString("name_nl"), rs.getString("name_en")),
                rs.getString("description"), rs.getBoolean("active"), new TreeSet<>(SqlArrays.read(rs, "permissions")),
                rs.getLong("version"));
    }
}

package gov.itms.iam.infrastructure;

import gov.itms.iam.application.PermissionRepository;
import gov.itms.iam.domain.BilingualName;
import gov.itms.iam.domain.Permission;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcPermissionRepository implements PermissionRepository {

    private final JdbcClient jdbc;

    JdbcPermissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Permission> findAll() {
        return jdbc.sql("select code, service, description_nl, description_en from permission order by code")
                .query((rs, n) -> new Permission(rs.getString("code"), rs.getString("service"),
                        new BilingualName(rs.getString("description_nl"), rs.getString("description_en"))))
                .list();
    }

    @Override
    public Set<String> existing(Collection<String> codes) {
        if (codes.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select code from permission where code = any(string_to_array(:codes, ','))")
                .param("codes", SqlArrays.join(codes))
                .query(String.class).list());
    }
}

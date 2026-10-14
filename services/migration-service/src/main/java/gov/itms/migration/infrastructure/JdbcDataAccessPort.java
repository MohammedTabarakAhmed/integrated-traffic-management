package gov.itms.migration.infrastructure;

import gov.itms.migration.application.Ports;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcDataAccessPort implements Ports.DataAccessPort {
    private final JdbcClient jdbc;

    public JdbcDataAccessPort(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Map<String, Object>> queryLegacy(String systemCode, String query, Object... params) {
        // In production, this would connect to the legacy system's database
        // For now, we use a local staging table pattern
        return jdbc.sql(query).params(params).query((rs, row) -> {
            Map<String, Object> map = new java.util.HashMap<>();
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                map.put(meta.getColumnName(i), rs.getObject(i));
            }
            return map;
        }).list();
    }

    @Override
    public long countLegacy(String systemCode, String table, String whereClause, Object... params) {
        var sql = "select count(*) from " + table + (whereClause != null && !whereClause.isBlank() ? " where " + whereClause : "");
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    @Override
    public void executeTarget(String sql, Object... params) {
        jdbc.sql(sql).params(params).update();
    }

    @Override
    public long countTarget(String table, String whereClause, Object... params) {
        var sql = "select count(*) from " + table + (whereClause != null && !whereClause.isBlank() ? " where " + whereClause : "");
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }
}
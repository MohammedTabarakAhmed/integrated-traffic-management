package gov.itms.ai.infrastructure;

import gov.itms.ai.application.Ports;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC implementation of FeatureStore.
 */
@Component
public class JdbcFeatureStore implements Ports.FeatureStore {

    private final JdbcTemplate jdbc;

    public JdbcFeatureStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Object> getFeatures(String entityType, String entityId, Instant asOf) {
        String sql = """
            SELECT features FROM ai_feature_vector
            WHERE entity_type = ? AND entity_id = ? AND valid_from <= ?
            ORDER BY valid_from DESC LIMIT 1
            """;
        return jdbc.query(sql, rs -> {
            if (rs.next()) {
                // Parse JSON features
                return Map.of();
            }
            return Map.of();
        }, entityType, entityId, asOf);
    }

    @Override
    public void putFeatures(String entityType, String entityId, Map<String, Object> features, Instant at) {
        String sql = """
            INSERT INTO ai_feature_vector (entity_type, entity_id, features, valid_from)
            VALUES (?, ?, ?::jsonb, ?)
            ON CONFLICT (entity_type, entity_id, valid_from) DO UPDATE SET features = ?::jsonb
            """;
        jdbc.update(sql, entityType, entityId, toJson(features), at, toJson(features));
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        return "{}";
    }
}
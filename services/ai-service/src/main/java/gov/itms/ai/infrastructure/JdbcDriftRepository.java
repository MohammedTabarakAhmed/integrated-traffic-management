package gov.itms.ai.infrastructure;

import gov.itms.ai.application.Ports;
import gov.itms.ai.domain.Models;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of DriftRepository.
 */
@Repository
@Transactional
public class JdbcDriftRepository implements Ports.DriftRepository {

    private final JdbcTemplate jdbc;

    public JdbcDriftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertMetrics(Models.DriftMetrics metrics) {
        String sql = """
            INSERT INTO ai_drift_metrics (model_id, window_start, window_end, psi, feature_drift_score,
                                          prediction_drift_score, concept_drift_score, feature_drift_details, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """;
        jdbc.update(sql,
                metrics.modelId(), metrics.windowStart(), metrics.windowEnd(),
                metrics.populationStabilityIndex(), metrics.featureDriftScore(),
                metrics.predictionDriftScore(), metrics.conceptDriftScore(),
                toJson(metrics.featureDriftDetails()), metrics.status().name()
        );
    }

    @Override
    public Optional<Models.DriftMetrics> findLatest(UUID modelId) {
        String sql = """
            SELECT * FROM ai_drift_metrics WHERE model_id = ?
            ORDER BY window_end DESC LIMIT 1
            """;
        List<Models.DriftMetrics> results = jdbc.query(sql, new DriftMetricsRowMapper(), modelId);
        return results.stream().findFirst();
    }

    @Override
    public List<Models.DriftMetrics> findByModelId(UUID modelId, Instant from, Instant to) {
        String sql = """
            SELECT * FROM ai_drift_metrics
            WHERE model_id = ? AND window_end BETWEEN ? AND ?
            ORDER BY window_end DESC
            """;
        return jdbc.query(sql, new DriftMetricsRowMapper(), modelId, from, to);
    }

    @Override
    public List<Models.DriftMetrics> findByStatus(Models.DriftStatus status) {
        String sql = "SELECT * FROM ai_drift_metrics WHERE status = ? ORDER BY window_end DESC";
        return jdbc.query(sql, new DriftMetricsRowMapper(), status.name());
    }

    private String toJson(Map<String, Double> map) {
        if (map == null || map.isEmpty()) return "{}";
        return "{}";
    }

    private static class DriftMetricsRowMapper implements RowMapper<Models.DriftMetrics> {
        @Override
        public Models.DriftMetrics mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Models.DriftMetrics(
                    UUID.fromString(rs.getString("model_id")),
                    rs.getTimestamp("window_start").toInstant(),
                    rs.getTimestamp("window_end").toInstant(),
                    rs.getDouble("psi"),
                    rs.getDouble("feature_drift_score"),
                    rs.getDouble("prediction_drift_score"),
                    rs.getDouble("concept_drift_score"),
                    Map.of(), // parseJson
                    Models.DriftStatus.valueOf(rs.getString("status"))
            );
        }
    }
}
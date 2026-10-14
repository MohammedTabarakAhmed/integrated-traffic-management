package gov.itms.ai.infrastructure;

import gov.itms.ai.application.Ports;
import gov.itms.ai.domain.Models;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of ExplanationRepository.
 */
@Transactional
public class JdbcExplanationRepository implements Ports.ExplanationRepository {

    private final JdbcTemplate jdbc;

    public JdbcExplanationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertExplanation(Models.Explanation explanation) {
        String sql = """
            INSERT INTO ai_explanation (inference_request_id, method, feature_importance, local_explanation,
                                        global_summary, generated_at)
            VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?)
            """;
        jdbc.update(sql,
                explanation.inferenceRequestId(), explanation.method().name(),
                toJson(explanation.featureImportance()), toJson(explanation.localExplanation()),
                explanation.globalExplanationSummary(), explanation.generatedAt()
        );
    }

    @Override
    public Optional<Models.Explanation> findByInferenceRequestId(UUID requestId) {
        String sql = "SELECT * FROM ai_explanation WHERE inference_request_id = ?";
        List<Models.Explanation> results = jdbc.query(sql, new ExplanationRowMapper(), requestId);
        return results.stream().findFirst();
    }

    @Override
    public List<Models.Explanation> findByModelId(UUID modelId, Instant from, Instant to) {
        String sql = """
            SELECT e.* FROM ai_explanation e
            JOIN ai_inference_response r ON e.inference_request_id = r.id
            WHERE r.model_id = ? AND e.generated_at BETWEEN ? AND ?
            ORDER BY e.generated_at DESC
            """;
        return jdbc.query(sql, new ExplanationRowMapper(), modelId, from, to);
    }

    private String toJson(Map<String, ?> map) {
        if (map == null || map.isEmpty()) return "{}";
        return "{}";
    }


    private static class ExplanationRowMapper implements RowMapper<Models.Explanation> {
        @Override
        public Models.Explanation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Models.Explanation(
                    UUID.fromString(rs.getString("inference_request_id")),
                    Models.ExplanationMethod.valueOf(rs.getString("method")),
                    Map.of(), // parseJson
                    Map.of(), // parseJson
                    rs.getString("global_summary"),
                    rs.getTimestamp("generated_at").toInstant()
            );
        }
    }
}
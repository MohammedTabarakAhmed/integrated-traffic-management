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
 * JDBC implementation of InferenceLogRepository.
 */
@Transactional
public class JdbcInferenceLogRepository implements Ports.InferenceLogRepository {

    private final JdbcTemplate jdbc;

    public JdbcInferenceLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertRequest(Models.InferenceRequest request) {
        String sql = """
            INSERT INTO ai_inference_request (id, use_case_id, correlation_id, input, context, requested_at, requested_by)
            VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
            """;
        jdbc.update(sql,
                request.requestId(), request.useCaseId(), request.correlationId(),
                toJson(request.input()), toJson(request.context()),
                request.requestedAt(), request.requestedBy()
        );
    }

    @Override
    public void insertResponse(Models.InferenceResponse response) {
        String sql = """
            INSERT INTO ai_inference_response (id, use_case_id, model_id, model_version, output, confidence,
                                               metadata, status, completed_at, latency_ms)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?)
            """;
        jdbc.update(sql,
                response.requestId(), response.useCaseId(), response.modelId(),
                response.modelVersion(), toJson(response.output()),
                response.confidence(), toJson(response.explanation()),
                response.status().name(), response.completedAt(), response.latencyMs()
        );
    }

    @Override
    public Optional<Models.InferenceRequest> findRequest(UUID requestId) {
        String sql = "SELECT * FROM ai_inference_request WHERE id = ?";
        List<Models.InferenceRequest> results = jdbc.query(sql, new RequestRowMapper(), requestId);
        return results.stream().findFirst();
    }

    @Override
    public Optional<Models.InferenceResponse> findResponse(UUID requestId) {
        String sql = "SELECT * FROM ai_inference_response WHERE id = ?";
        List<Models.InferenceResponse> results = jdbc.query(sql, new ResponseRowMapper(), requestId);
        return results.stream().findFirst();
    }

    @Override
    public List<Models.InferenceResponse> findByUseCaseId(UUID useCaseId, Instant from, Instant to, int limit) {
        String sql = """
            SELECT * FROM ai_inference_response
            WHERE use_case_id = ? AND completed_at BETWEEN ? AND ?
            ORDER BY completed_at DESC LIMIT ?
            """;
        return jdbc.query(sql, new ResponseRowMapper(), useCaseId, from, to, limit);
    }

    @Override
    public List<Models.InferenceResponse> findByModelId(UUID modelId, Instant from, Instant to, int limit) {
        String sql = """
            SELECT * FROM ai_inference_response
            WHERE model_id = ? AND completed_at BETWEEN ? AND ?
            ORDER BY completed_at DESC LIMIT ?
            """;
        return jdbc.query(sql, new ResponseRowMapper(), modelId, from, to, limit);
    }

    @Override
    public List<Models.InferenceResponse> findRequiringReview(Instant from, Instant to, int limit) {
        String sql = """
            SELECT * FROM ai_inference_response
            WHERE status IN ('HUMAN_REVIEW_REQUIRED', 'LOW_CONFIDENCE')
              AND completed_at BETWEEN ? AND ?
            ORDER BY completed_at DESC LIMIT ?
            """;
        return jdbc.query(sql, new ResponseRowMapper(), from, to, limit);
    }

    private String toJson(Map<String, ?> map) {
        if (map == null || map.isEmpty()) return "{}";
        // Simplified - use Jackson in production
        return "{}";
    }

    private static class RequestRowMapper implements RowMapper<Models.InferenceRequest> {
        @Override
        public Models.InferenceRequest mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Models.InferenceRequest(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("use_case_id")),
                    rs.getString("correlation_id"),
                    Map.of(), // parseJson
                    Map.of(), // parseJson
                    rs.getTimestamp("requested_at").toInstant(),
                    rs.getString("requested_by")
            );
        }
    }

    private static class ResponseRowMapper implements RowMapper<Models.InferenceResponse> {
        @Override
        public Models.InferenceResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Models.InferenceResponse(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("use_case_id")),
                    rs.getString("model_id") != null ? UUID.fromString(rs.getString("model_id")) : null,
                    rs.getString("model_version"),
                    Map.of(), // parseJson
                    rs.getDouble("confidence"),
                    Map.of(), // parseJson
                    Models.InferenceStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("completed_at").toInstant(),
                    rs.getLong("latency_ms")
            );
        }
    }
}
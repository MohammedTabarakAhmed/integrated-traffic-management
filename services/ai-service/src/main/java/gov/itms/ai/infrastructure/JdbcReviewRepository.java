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
 * JDBC implementation of ReviewRepository.
 */
@Repository
@Transactional
public class JdbcReviewRepository implements Ports.ReviewRepository {

    private final JdbcTemplate jdbc;

    public JdbcReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertCase(Models.ReviewCase reviewCase) {
        String sql = """
            INSERT INTO ai_review_case (id, inference_request_id, use_case_id, status, assigned_reviewer,
                                         inference_output, reviewer_decision, reviewer_notes, created_at, reviewed_at, reviewed_by)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
            """;
        jdbc.update(sql,
                reviewCase.id(), reviewCase.inferenceRequestId(), reviewCase.useCaseId(),
                reviewCase.status(), reviewCase.assignedReviewer(),
                toJson(reviewCase.inferenceOutput()), toJson(reviewCase.reviewerDecision()),
                reviewCase.reviewerNotes(), reviewCase.createdAt(),
                reviewCase.reviewedAt(), reviewCase.reviewedBy()
        );
    }

    @Override
    public Optional<Models.ReviewCase> findById(UUID id) {
        String sql = "SELECT * FROM ai_review_case WHERE id = ?";
        List<Models.ReviewCase> results = jdbc.query(sql, new ReviewCaseRowMapper(), id);
        return results.stream().findFirst();
    }

    @Override
    public List<Models.ReviewCase> findByStatus(String status, int limit, int offset) {
        String sql = "SELECT * FROM ai_review_case WHERE status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql, new ReviewCaseRowMapper(), status, limit, offset);
    }

    @Override
    public List<Models.ReviewCase> findByAssignee(String assignee, int limit, int offset) {
        String sql = "SELECT * FROM ai_review_case WHERE assigned_reviewer = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql, new ReviewCaseRowMapper(), assignee, limit, offset);
    }

    @Override
    public List<Models.ReviewCase> findByUseCaseId(UUID useCaseId, Instant from, Instant to, int limit) {
        String sql = """
            SELECT * FROM ai_review_case
            WHERE use_case_id = ? AND created_at BETWEEN ? AND ?
            ORDER BY created_at DESC LIMIT ?
            """;
        return jdbc.query(sql, new ReviewCaseRowMapper(), useCaseId, from, to, limit);
    }

    @Override
    public void updateCase(Models.ReviewCase reviewCase) {
        String sql = """
            UPDATE ai_review_case SET status = ?, assigned_reviewer = ?, reviewer_decision = ?::jsonb,
                                      reviewer_notes = ?, reviewed_at = ?, reviewed_by = ?
            WHERE id = ?
            """;
        jdbc.update(sql,
                reviewCase.status(), reviewCase.assignedReviewer(),
                toJson(reviewCase.reviewerDecision()), reviewCase.reviewerNotes(),
                reviewCase.reviewedAt(), reviewCase.reviewedBy(), reviewCase.id()
        );
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        return "{}";
    }

    private static class ReviewCaseRowMapper implements RowMapper<Models.ReviewCase> {
        @Override
        public Models.ReviewCase mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Models.ReviewCase(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("inference_request_id")),
                    UUID.fromString(rs.getString("use_case_id")),
                    rs.getString("status"),
                    rs.getString("assigned_reviewer"),
                    Map.of(), // parseJson
                    Map.of(), // parseJson
                    rs.getString("reviewer_notes"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                    rs.getString("reviewed_by")
            );
        }
    }
}
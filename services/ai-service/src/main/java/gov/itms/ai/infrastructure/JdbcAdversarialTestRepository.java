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
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of AdversarialTestRepository.
 */
@Repository
@Transactional
public class JdbcAdversarialTestRepository implements Ports.AdversarialTestRepository {

    private final JdbcTemplate jdbc;

    public JdbcAdversarialTestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertResult(Models.AdversarialTestResult result) {
        String sql = """
            INSERT INTO ai_adversarial_test (model_id, attack_type, robustness_score, samples_tested,
                                             samples_failed, status, tested_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        jdbc.update(sql,
                result.modelId(), result.attackType(), result.robustnessScore(),
                result.samplesTested(), result.samplesFailed(), result.status().name(), result.testedAt()
        );
    }

    @Override
    public List<Models.AdversarialTestResult> findByModelId(UUID modelId) {
        String sql = "SELECT * FROM ai_adversarial_test WHERE model_id = ? ORDER BY tested_at DESC";
        return jdbc.query(sql, new AdversarialTestRowMapper(), modelId);
    }

    @Override
    public List<Models.AdversarialTestResult> findLatestByModelId(UUID modelId) {
        String sql = """
            SELECT DISTINCT ON (attack_type) *
            FROM ai_adversarial_test WHERE model_id = ?
            ORDER BY attack_type, tested_at DESC
            """;
        return jdbc.query(sql, new AdversarialTestRowMapper(), modelId);
    }

    @Override
    public List<Models.AdversarialTestResult> findByStatus(Models.AdversarialStatus status) {
        String sql = "SELECT * FROM ai_adversarial_test WHERE status = ? ORDER BY tested_at DESC";
        return jdbc.query(sql, new AdversarialTestRowMapper(), status.name());
    }

    private static class AdversarialTestRowMapper implements RowMapper<Models.AdversarialTestResult> {
        @Override
        public Models.AdversarialTestResult mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Models.AdversarialTestResult(
                    UUID.fromString(rs.getString("model_id")),
                    rs.getString("attack_type"),
                    rs.getDouble("robustness_score"),
                    rs.getInt("samples_tested"),
                    rs.getInt("samples_failed"),
                    Models.AdversarialStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("tested_at").toInstant()
            );
        }
    }
}
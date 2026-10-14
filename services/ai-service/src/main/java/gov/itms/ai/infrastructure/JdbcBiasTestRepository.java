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
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of BiasTestRepository.
 */
@Transactional
public class JdbcBiasTestRepository implements Ports.BiasTestRepository {

    private final JdbcTemplate jdbc;

    public JdbcBiasTestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertResult(Models.BiasTestResult result) {
        String sql = """
            INSERT INTO ai_bias_test (model_id, protected_attribute, reference_group, comparison_group,
                                      demographic_parity_diff, equal_opportunity_diff, equalized_odds_diff, status, tested_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbc.update(sql,
                result.modelId(), result.protectedAttribute(), result.referenceGroup(),
                result.comparisonGroup(), result.demographicParityDifference(),
                result.equalOpportunityDifference(), result.equalizedOddsDifference(),
                result.status().name(), result.testedAt()
        );
    }

    @Override
    public List<Models.BiasTestResult> findByModelId(UUID modelId) {
        String sql = "SELECT * FROM ai_bias_test WHERE model_id = ? ORDER BY tested_at DESC";
        return jdbc.query(sql, new BiasTestRowMapper(), modelId);
    }

    @Override
    public List<Models.BiasTestResult> findLatestByModelId(UUID modelId) {
        String sql = """
            SELECT DISTINCT ON (protected_attribute, reference_group, comparison_group) *
            FROM ai_bias_test WHERE model_id = ?
            ORDER BY protected_attribute, reference_group, comparison_group, tested_at DESC
            """;
        return jdbc.query(sql, new BiasTestRowMapper(), modelId);
    }

    @Override
    public List<Models.BiasTestResult> findByStatus(Models.BiasStatus status) {
        String sql = "SELECT * FROM ai_bias_test WHERE status = ? ORDER BY tested_at DESC";
        return jdbc.query(sql, new BiasTestRowMapper(), status.name());
    }

    private static class BiasTestRowMapper implements RowMapper<Models.BiasTestResult> {
        @Override
        public Models.BiasTestResult mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Models.BiasTestResult(
                    UUID.fromString(rs.getString("model_id")),
                    rs.getString("protected_attribute"),
                    rs.getString("reference_group"),
                    rs.getString("comparison_group"),
                    rs.getDouble("demographic_parity_diff"),
                    rs.getDouble("equal_opportunity_diff"),
                    rs.getDouble("equalized_odds_diff"),
                    Models.BiasStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("tested_at").toInstant()
            );
        }
    }
}
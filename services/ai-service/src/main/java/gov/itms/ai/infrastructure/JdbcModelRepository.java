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
 * JDBC implementation of ModelRepository.
 */
@Repository
@Transactional
public class JdbcModelRepository implements Ports.ModelRepository {

    private final JdbcTemplate jdbc;

    public JdbcModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertModel(Models.Model model) {
        String sql = """
            INSERT INTO ai_model (id, name, version, description, type, status, owner, framework,
                                  artifact_uri, hyperparameters, metrics, accuracy_targets,
                                  created_at, updated_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
            """;
        jdbc.update(sql,
                model.id(), model.name(), model.version(), model.description(),
                model.type().name(), model.status().name(), model.owner(),
                model.framework(), model.artifactUri(),
                toJson(model.hyperparameters()), toJson(model.metrics()),
                toJsonAccuracyTargets(model.accuracyTargets()),
                model.createdAt(), model.updatedAt(), model.createdBy()
        );
    }

    @Override
    public Optional<Models.Model> findById(UUID id) {
        String sql = "SELECT * FROM ai_model WHERE id = ?";
        List<Models.Model> results = jdbc.query(sql, new ModelRowMapper(), id);
        return results.stream().findFirst();
    }

    @Override
    public Optional<Models.Model> findByNameAndVersion(String name, String version) {
        String sql = "SELECT * FROM ai_model WHERE name = ? AND version = ?";
        List<Models.Model> results = jdbc.query(sql, new ModelRowMapper(), name, version);
        return results.stream().findFirst();
    }

    @Override
    public List<Models.Model> findByName(String name) {
        String sql = "SELECT * FROM ai_model WHERE name = ? ORDER BY version DESC";
        return jdbc.query(sql, new ModelRowMapper(), name);
    }

    @Override
    public List<Models.Model> findByStatus(Models.ModelStatus status) {
        String sql = "SELECT * FROM ai_model WHERE status = ? ORDER BY updated_at DESC";
        return jdbc.query(sql, new ModelRowMapper(), status.name());
    }

    @Override
    public List<Models.Model> findByType(Models.ModelType type) {
        String sql = "SELECT * FROM ai_model WHERE type = ? ORDER BY updated_at DESC";
        return jdbc.query(sql, new ModelRowMapper(), type.name());
    }

    @Override
    public List<Models.Model> findAll(int limit, int offset) {
        String sql = "SELECT * FROM ai_model ORDER BY updated_at DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql, new ModelRowMapper(), limit, offset);
    }

    @Override
    public void updateModel(Models.Model model) {
        String sql = """
            UPDATE ai_model SET name = ?, version = ?, description = ?, type = ?, status = ?,
                               owner = ?, framework = ?, artifact_uri = ?, hyperparameters = ?::jsonb,
                               metrics = ?::jsonb, accuracy_targets = ?::jsonb, updated_at = ?, updated_by = ?
            WHERE id = ?
            """;
        jdbc.update(sql,
                model.name(), model.version(), model.description(),
                model.type().name(), model.status().name(),
                model.owner(), model.framework(), model.artifactUri(),
                toJson(model.hyperparameters()), toJson(model.metrics()),
                toJsonAccuracyTargets(model.accuracyTargets()),
                model.updatedAt(), model.createdBy(), model.id()
        );
    }

    @Override
    public void updateStatus(UUID id, Models.ModelStatus status) {
        String sql = "UPDATE ai_model SET status = ?, updated_at = ? WHERE id = ?";
        jdbc.update(sql, status.name(), Instant.now(), id);
    }

    @Override
    public void insertDeployment(Models.ModelDeployment deployment) {
        String sql = """
            INSERT INTO ai_model_deployment (id, model_id, environment, version, status,
                                             deployed_by, deployed_at, rolled_back_at, rollback_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbc.update(sql,
                deployment.id(), deployment.modelId(), deployment.environment(),
                deployment.version(), deployment.status().name(),
                deployment.deployedBy(), deployment.deployedAt(),
                deployment.rolledBackAt(), deployment.rollbackReason()
        );
    }

    @Override
    public Optional<Models.ModelDeployment> findActiveDeployment(UUID modelId, String environment) {
        String sql = """
            SELECT * FROM ai_model_deployment
            WHERE model_id = ? AND environment = ? AND status = 'ACTIVE'
            ORDER BY deployed_at DESC LIMIT 1
            """;
        List<Models.ModelDeployment> results = jdbc.query(sql, new DeploymentRowMapper(), modelId, environment);
        return results.stream().findFirst();
    }

    @Override
    public List<Models.ModelDeployment> findDeployments(UUID modelId) {
        String sql = "SELECT * FROM ai_model_deployment WHERE model_id = ? ORDER BY deployed_at DESC";
        return jdbc.query(sql, new DeploymentRowMapper(), modelId);
    }

    private String toJson(Map<String, ?> map) {
        if (map == null || map.isEmpty()) return "{}";
        return map.entrySet().stream()
                .map(e -> "\"%s\":\"%s\"".formatted(e.getKey(), String.valueOf(e.getValue()).replace("\"", "\\\"")))
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private String toJsonAccuracyTargets(Models.AccuracyTargets targets) {
        if (targets == null) return "{}";
        return """
            {"minAccuracy":%f,"minPrecision":%f,"minRecall":%f,"minF1Score":%f,
             "maxFalsePositiveRate":%f,"maxFalseNegativeRate":%f,"customThresholds":%s}
            """.formatted(
                    targets.minAccuracy(), targets.minPrecision(), targets.minRecall(),
                    targets.minF1Score(), targets.maxFalsePositiveRate(), targets.maxFalseNegativeRate(),
                    toJson(targets.customThresholds())
            );
    }

    private static class ModelRowMapper implements RowMapper<Models.Model> {
        @Override
        public Models.Model mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Models.Model(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("name"),
                    rs.getString("version"),
                    rs.getString("description"),
                    Models.ModelType.valueOf(rs.getString("type")),
                    Models.ModelStatus.valueOf(rs.getString("status")),
                    rs.getString("owner"),
                    rs.getString("framework"),
                    rs.getString("artifact_uri"),
                    parseJsonMap(rs.getString("hyperparameters")),
                    parseJsonMap(rs.getString("metrics")),
                    parseAccuracyTargets(rs.getString("accuracy_targets")),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getString("created_by")
            );
        }
    }

    private static class DeploymentRowMapper implements RowMapper<Models.ModelDeployment> {
        @Override
        public Models.ModelDeployment mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Models.ModelDeployment(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("model_id")),
                    rs.getString("environment"),
                    rs.getString("version"),
                    Models.DeploymentStatus.valueOf(rs.getString("status")),
                    rs.getString("deployed_by"),
                    rs.getTimestamp("deployed_at").toInstant(),
                    rs.getTimestamp("rolled_back_at") != null ? rs.getTimestamp("rolled_back_at").toInstant() : null,
                    rs.getString("rollback_reason")
            );
        }
    }

    private static Map<String, String> parseJsonMap(String json) {
        if (json == null || json.equals("{}")) return Map.of();
        // Simplified JSON parsing - in production use Jackson/JSON-B
        return Map.of();
    }

    private static Models.AccuracyTargets parseAccuracyTargets(String json) {
        if (json == null || json.equals("{}")) return Models.AccuracyTargets.defaultTargets();
        return Models.AccuracyTargets.defaultTargets();
    }
}
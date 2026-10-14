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
 * JDBC implementation of UseCaseRepository.
 */
@Transactional
public class JdbcUseCaseRepository implements Ports.UseCaseRepository {

    private final JdbcTemplate jdbc;

    public JdbcUseCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertUseCase(Models.UseCase useCase) {
        String sql = """
            INSERT INTO ai_use_case (id, code, name, description, requirement_refs, status, owner,
                                     model_id, human_review, kill_switch, data_readiness,
                                     created_at, updated_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
            """;
        jdbc.update(sql,
                useCase.id(), useCase.code(), useCase.name(), useCase.description(),
                toJsonArray(useCase.requirementRefs()), useCase.status().name(), useCase.owner(),
                useCase.model() != null ? useCase.model().id() : null,
                toJsonHumanReview(useCase.humanReview()),
                toJsonKillSwitch(useCase.killSwitch()),
                toJsonDataReadiness(useCase.dataReadiness()),
                useCase.createdAt(), useCase.updatedAt()
        );
    }

    @Override
    public Optional<Models.UseCase> findById(UUID id) {
        String sql = "SELECT * FROM ai_use_case WHERE id = ?";
        List<Models.UseCase> results = jdbc.query(sql, new UseCaseRowMapper(), id);
        return results.stream().findFirst();
    }

    @Override
    public Optional<Models.UseCase> findByCode(String code) {
        String sql = "SELECT * FROM ai_use_case WHERE code = ?";
        List<Models.UseCase> results = jdbc.query(sql, new UseCaseRowMapper(), code);
        return results.stream().findFirst();
    }

    @Override
    public List<Models.UseCase> findByStatus(Models.UseCaseStatus status) {
        String sql = "SELECT * FROM ai_use_case WHERE status = ? ORDER BY updated_at DESC";
        return jdbc.query(sql, new UseCaseRowMapper(), status.name());
    }

    @Override
    public List<Models.UseCase> findByModelId(UUID modelId) {
        String sql = "SELECT * FROM ai_use_case WHERE model_id = ? ORDER BY updated_at DESC";
        return jdbc.query(sql, new UseCaseRowMapper(), modelId);
    }

    @Override
    public List<Models.UseCase> findAll(int limit, int offset) {
        String sql = "SELECT * FROM ai_use_case ORDER BY updated_at DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql, new UseCaseRowMapper(), limit, offset);
    }

    @Override
    public void updateUseCase(Models.UseCase useCase) {
        String sql = """
            UPDATE ai_use_case SET code = ?, name = ?, description = ?, requirement_refs = ?::jsonb,
                                   status = ?, owner = ?, model_id = ?, human_review = ?::jsonb,
                                   kill_switch = ?::jsonb, data_readiness = ?::jsonb, updated_at = ?
            WHERE id = ?
            """;
        jdbc.update(sql,
                useCase.code(), useCase.name(), useCase.description(),
                toJsonArray(useCase.requirementRefs()), useCase.status().name(), useCase.owner(),
                useCase.model() != null ? useCase.model().id() : null,
                toJsonHumanReview(useCase.humanReview()),
                toJsonKillSwitch(useCase.killSwitch()),
                toJsonDataReadiness(useCase.dataReadiness()),
                useCase.updatedAt(), useCase.id()
        );
    }

    @Override
    public void updateStatus(UUID id, Models.UseCaseStatus status) {
        String sql = "UPDATE ai_use_case SET status = ?, updated_at = ? WHERE id = ?";
        jdbc.update(sql, status.name(), Instant.now(), id);
    }

    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        return list.stream().map(s -> "\"%s\"".formatted(s.replace("\"", "\\\"")))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private String toJsonHumanReview(Models.HumanReviewConfig config) {
        if (config == null) return "null";
        return """
            {"enabled":%b,"trigger":"%s","reviewerRole":"%s","slaHours":%d,
             "requireConsensus":%b,"minReviewers":%d,"escalation":{"hoursBeforeEscalation":%d,"escalationRole":"%s"}}
            """.formatted(
                    config.enabled(), config.trigger().name(), config.reviewerRole(),
                    config.slaHours(), config.requireConsensus(), config.minReviewers(),
                    config.escalation().hoursBeforeEscalation(), config.escalation().escalationRole()
            );
    }

    private String toJsonKillSwitch(Models.KillSwitchConfig config) {
        if (config == null) return "null";
        return """
            {"enabled":%b,"activationReason":"%s","activatedBy":"%s","activatedAt":%s,
             "scope":"%s","rollback":{"autoRollback":%b,"targetVersion":"%s"}}
            """.formatted(
                    config.enabled(),
                    config.activationReason() != null ? config.activationReason() : "",
                    config.activatedBy() != null ? config.activatedBy() : "",
                    config.activatedAt() != null ? "\"" + config.activatedAt().toString() + "\"" : "null",
                    config.scope().name(),
                    config.rollback().autoRollback(),
                    config.rollback().targetVersion()
            );
    }

    private String toJsonDataReadiness(Models.DataReadiness readiness) {
        if (readiness == null) return "null";
        return """
            {"assessed":%b,"completenessScore":%f,"qualityScore":%f,"biasScore":%f,
             "gaps":%s,"assessedAt":%s,"assessedBy":"%s"}
            """.formatted(
                    readiness.assessed(), readiness.completenessScore(), readiness.qualityScore(),
                    readiness.biasScore(), toJsonArray(readiness.gaps()),
                    readiness.assessedAt() != null ? "\"" + readiness.assessedAt().toString() + "\"" : "null",
                    readiness.assessedBy() != null ? readiness.assessedBy() : ""
            );
    }

    private static class UseCaseRowMapper implements RowMapper<Models.UseCase> {
        @Override
        public Models.UseCase mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Models.UseCase(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("description"),
                    parseJsonArray(rs.getString("requirement_refs")),
                    Models.UseCaseStatus.valueOf(rs.getString("status")),
                    rs.getString("owner"),
                    null, // model loaded separately
                    parseHumanReview(rs.getString("human_review")),
                    parseKillSwitch(rs.getString("kill_switch")),
                    parseDataReadiness(rs.getString("data_readiness")),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );
        }
    }

    private static List<String> parseJsonArray(String json) {
        if (json == null || json.equals("[]")) return List.of();
        return List.of();
    }

    private static Models.HumanReviewConfig parseHumanReview(String json) {
        if (json == null || json.equals("null")) return null;
        return new Models.HumanReviewConfig(false, Models.ReviewTrigger.ALWAYS, "", 0, false, 0,
                new Models.EscalationConfig(24, "AI_ADMIN"));
    }

    private static Models.KillSwitchConfig parseKillSwitch(String json) {
        if (json == null || json.equals("null")) return null;
        return new Models.KillSwitchConfig(false, null, null, null, Models.KillSwitchScope.USE_CASE,
                new Models.RollbackConfig(true, "PREVIOUS_STABLE"));
    }

    private static Models.DataReadiness parseDataReadiness(String json) {
        if (json == null || json.equals("null")) return null;
        return new Models.DataReadiness(false, 0, 0, 0, List.of(), null, null);
    }
}
package gov.itms.ai.application;

import gov.itms.ai.domain.AiModels;
import gov.itms.ai.domain.Models;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ports of the AI service. The model, use-case, inference, review and assurance ports follow the methods the
 * application services call and the JDBC adapters implement (domain types in {@link Models}); the agent ports keep the
 * agent design ({@link AiModels}).
 */
public final class Ports {
    private Ports() { }

    // ---------- agents (AiModels) ----------

    public interface AgentRepository {
        Optional<AiModels.Agent> findByCode(String code);
        List<AiModels.Agent> findAll();
        void save(AiModels.Agent agent);
        void delete(String code);
    }

    public interface AgentVersionRepository {
        Optional<AiModels.AgentVersion> findByAgentAndVersion(UUID agentId, int version);
        List<AiModels.AgentVersion> findByAgent(UUID agentId);
        void save(AiModels.AgentVersion version);
    }

    public interface AgentExecutionRepository {
        void save(AiModels.AgentExecution execution);
        Optional<AiModels.AgentExecution> findById(UUID id);
        List<AiModels.AgentExecution> findByAgent(UUID agentId, int limit);
        List<AiModels.AgentExecution> findRequiringReview(String reviewerRole);
    }

    public interface HumanReviewRepository {
        void save(AiModels.HumanReview review);
        Optional<AiModels.HumanReview> findById(UUID id);
        List<AiModels.HumanReview> findByExecution(UUID executionId);
    }

    public interface AgentAuditRepository {
        void save(AiModels.AgentAuditLog log);
        List<AiModels.AgentAuditLog> findByAgent(UUID agentId, Instant from, Instant to);
        List<AiModels.AgentAuditLog> findByExecution(UUID executionId);
    }

    public interface ModelEvaluationRepository {
        void save(AiModels.ModelEvaluation eval);
        List<AiModels.ModelEvaluation> findByModel(String modelName, String modelVersion);
    }

    public interface DriftAlertRepository {
        void save(AiModels.DriftAlert alert);
        List<AiModels.DriftAlert> findActive();
        void resolve(UUID id, String resolvedBy, Instant at);
    }

    // ---------- models and use cases (Models) ----------

    public interface ModelRepository {
        void insertModel(Models.Model model);
        Optional<Models.Model> findById(UUID id);
        Optional<Models.Model> findByNameAndVersion(String name, String version);
        List<Models.Model> findByName(String name);
        List<Models.Model> findByStatus(Models.ModelStatus status);
        List<Models.Model> findByType(Models.ModelType type);
        List<Models.Model> findAll(int limit, int offset);
        void updateModel(Models.Model model);
        void updateStatus(UUID id, Models.ModelStatus status);
        void insertDeployment(Models.ModelDeployment deployment);
        Optional<Models.ModelDeployment> findActiveDeployment(UUID modelId, String environment);
        List<Models.ModelDeployment> findDeployments(UUID modelId);
    }

    public interface UseCaseRepository {
        void insertUseCase(Models.UseCase useCase);
        Optional<Models.UseCase> findById(UUID id);
        Optional<Models.UseCase> findByCode(String code);
        List<Models.UseCase> findByStatus(Models.UseCaseStatus status);
        List<Models.UseCase> findByModelId(UUID modelId);
        List<Models.UseCase> findAll(int limit, int offset);
        void updateUseCase(Models.UseCase useCase);
        void updateStatus(UUID id, Models.UseCaseStatus status);
    }

    // ---------- inference, review, explanation ----------

    public interface InferenceLogRepository {
        void insertRequest(Models.InferenceRequest request);
        void insertResponse(Models.InferenceResponse response);
        Optional<Models.InferenceRequest> findRequest(UUID requestId);
        Optional<Models.InferenceResponse> findResponse(UUID requestId);
        List<Models.InferenceResponse> findByUseCaseId(UUID useCaseId, Instant from, Instant to, int limit);
        List<Models.InferenceResponse> findByModelId(UUID modelId, Instant from, Instant to, int limit);
        List<Models.InferenceResponse> findRequiringReview(Instant from, Instant to, int limit);
    }

    public interface ReviewRepository {
        void insertCase(Models.ReviewCase reviewCase);
        Optional<Models.ReviewCase> findById(UUID id);
        List<Models.ReviewCase> findByStatus(String status, int limit, int offset);
        List<Models.ReviewCase> findByAssignee(String assignee, int limit, int offset);
        List<Models.ReviewCase> findByUseCaseId(UUID useCaseId, Instant from, Instant to, int limit);
        void updateCase(Models.ReviewCase reviewCase);
    }

    public interface ExplanationRepository {
        void insertExplanation(Models.Explanation explanation);
        Optional<Models.Explanation> findByInferenceRequestId(UUID requestId);
        List<Models.Explanation> findByModelId(UUID modelId, Instant from, Instant to);
    }

    // ---------- assurance: drift, bias, adversarial ----------

    public interface DriftRepository {
        void insertMetrics(Models.DriftMetrics metrics);
        Optional<Models.DriftMetrics> findLatest(UUID modelId);
        List<Models.DriftMetrics> findByModelId(UUID modelId, Instant from, Instant to);
        List<Models.DriftMetrics> findByStatus(Models.DriftStatus status);
    }

    public interface BiasTestRepository {
        void insertResult(Models.BiasTestResult result);
        List<Models.BiasTestResult> findByModelId(UUID modelId);
        List<Models.BiasTestResult> findLatestByModelId(UUID modelId);
        List<Models.BiasTestResult> findByStatus(Models.BiasStatus status);
    }

    public interface AdversarialTestRepository {
        void insertResult(Models.AdversarialTestResult result);
        List<Models.AdversarialTestResult> findByModelId(UUID modelId);
        List<Models.AdversarialTestResult> findLatestByModelId(UUID modelId);
        List<Models.AdversarialTestResult> findByStatus(Models.AdversarialStatus status);
    }

    // ---------- runtime ----------

    /** Emergency stop (AI rule: every agent has one); scoped to a use case, a model or the whole service. */
    public interface KillSwitch {
        void activate(String reason, String activatedBy, Models.KillSwitchScope scope);
        void deactivate(String deactivatedBy);
        boolean isActive(Models.KillSwitchScope scope);
        State getState();

        record State(boolean active, String reason, String activatedBy, Instant activatedAt, Models.KillSwitchScope scope) { }
    }

    public interface ModelInference {
        Prediction predict(Models.Model model, Map<String, Object> input);
        boolean isHealthy(Models.Model model);

        record Prediction(Map<String, Object> output, double confidence, Map<String, Object> metadata) { }
    }

    public interface ModelEvaluator {
        EvaluationResult evaluate(Models.Model model, List<Map<String, Object>> testData);

        record EvaluationResult(double accuracy, double precision, double recall, double f1Score,
                                double falsePositiveRate, double falseNegativeRate, Map<String, Object> details,
                                boolean meetsTargets) { }
    }

    public interface FeatureStore {
        Map<String, Object> getFeatures(String entityType, String entityId, Instant asOf);
        void putFeatures(String entityType, String entityId, Map<String, Object> features, Instant at);
    }

    public interface ToolExecutor {
        Map<String, Object> execute(String toolName, Map<String, Object> input, String actorId);
    }

    public interface ModelInvoker {
        Map<String, Object> invoke(String modelName, String modelVersion, String prompt, Map<String, Object> params);
    }
}

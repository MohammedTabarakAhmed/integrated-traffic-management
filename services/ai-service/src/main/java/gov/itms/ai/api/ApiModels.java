package gov.itms.ai.api;

import gov.itms.ai.application.Ports;
import gov.itms.ai.domain.Models;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API request/response models for ai-service.
 */
public final class ApiModels {

    private ApiModels() {}

    // Model API
    public record RegisterModelRequest(
            String name,
            String version,
            String description,
            String type,
            String owner,
            String framework,
            String artifactUri,
            Map<String, String> hyperparameters,
            AccuracyTargetsRequest accuracyTargets
    ) {}

    public record AccuracyTargetsRequest(
            Double minAccuracy,
            Double minPrecision,
            Double minRecall,
            Double minF1Score,
            Double maxFalsePositiveRate,
            Double maxFalseNegativeRate,
            Map<String, Double> customThresholds
    ) {
        public Models.AccuracyTargets toDomain() {
            return new Models.AccuracyTargets(
                    minAccuracy != null ? minAccuracy : 0.85,
                    minPrecision != null ? minPrecision : 0.80,
                    minRecall != null ? minRecall : 0.80,
                    minF1Score != null ? minF1Score : 0.80,
                    maxFalsePositiveRate != null ? maxFalsePositiveRate : 0.10,
                    maxFalseNegativeRate != null ? maxFalseNegativeRate : 0.10,
                    customThresholds != null ? customThresholds : Map.of()
            );
        }
    }

    public record ModelResponse(
            UUID id,
            String name,
            String version,
            String description,
            String type,
            String status,
            String owner,
            String framework,
            String artifactUri,
            Map<String, String> hyperparameters,
            Map<String, String> metrics,
            AccuracyTargetsResponse accuracyTargets,
            Instant createdAt,
            Instant updatedAt,
            String createdBy
    ) {
        public static ModelResponse from(Models.Model model) {
            return new ModelResponse(
                    model.id(), model.name(), model.version(), model.description(),
                    model.type().name(), model.status().name(), model.owner(),
                    model.framework(), model.artifactUri(), model.hyperparameters(),
                    model.metrics(), new AccuracyTargetsResponse(model.accuracyTargets()),
                    model.createdAt(), model.updatedAt(), model.createdBy()
            );
        }
    }

    public record AccuracyTargetsResponse(
            double minAccuracy,
            double minPrecision,
            double minRecall,
            double minF1Score,
            double maxFalsePositiveRate,
            double maxFalseNegativeRate,
            Map<String, Double> customThresholds
    ) {
        public AccuracyTargetsResponse(Models.AccuracyTargets targets) {
            this(targets.minAccuracy(), targets.minPrecision(), targets.minRecall(),
                 targets.minF1Score(), targets.maxFalsePositiveRate(), targets.maxFalseNegativeRate(),
                 targets.customThresholds());
        }
    }

    // Use Case API
    public record RegisterUseCaseRequest(
            String code,
            String name,
            String description,
            List<String> requirementRefs,
            String owner,
            HumanReviewRequest humanReview,
            KillSwitchRequest killSwitch,
            DataReadinessRequest dataReadiness
    ) {}

    public record HumanReviewRequest(
            boolean enabled,
            String trigger,
            String reviewerRole,
            int slaHours,
            boolean requireConsensus,
            int minReviewers,
            EscalationRequest escalation
    ) {
        public Models.HumanReviewConfig toDomain() {
            return new Models.HumanReviewConfig(
                    enabled, Models.ReviewTrigger.valueOf(trigger), reviewerRole, slaHours,
                    requireConsensus, minReviewers,
                    escalation != null ? escalation.toDomain() : new Models.EscalationConfig(24, "AI_ADMIN")
            );
        }
    }

    public record EscalationRequest(int hoursBeforeEscalation, String escalationRole) {
        public Models.EscalationConfig toDomain() {
            return new Models.EscalationConfig(hoursBeforeEscalation, escalationRole);
        }
    }

    public record KillSwitchRequest(
            boolean enabled,
            String scope,
            RollbackRequest rollback
    ) {
        public Models.KillSwitchConfig toDomain() {
            return new Models.KillSwitchConfig(
                    enabled, null, null, null, Models.KillSwitchScope.valueOf(scope),
                    rollback != null ? rollback.toDomain() : new Models.RollbackConfig(true, "PREVIOUS_STABLE")
            );
        }
    }

    public record RollbackRequest(boolean autoRollback, String targetVersion) {
        public Models.RollbackConfig toDomain() {
            return new Models.RollbackConfig(autoRollback, targetVersion);
        }
    }

    public record DataReadinessRequest(
            double completenessScore,
            double qualityScore,
            double biasScore,
            List<String> gaps
    ) {
        public Models.DataReadiness toDomain() {
            return new Models.DataReadiness(
                    false, completenessScore, qualityScore, biasScore, gaps, null, null
            );
        }
    }

    public record UseCaseResponse(
            UUID id,
            String code,
            String name,
            String description,
            List<String> requirementRefs,
            String status,
            String owner,
            UUID modelId,
            String modelName,
            HumanReviewResponse humanReview,
            KillSwitchResponse killSwitch,
            DataReadinessResponse dataReadiness,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static UseCaseResponse from(Models.UseCase uc) {
            return new UseCaseResponse(
                    uc.id(), uc.code(), uc.name(), uc.description(),
                    uc.requirementRefs(), uc.status().name(), uc.owner(),
                    uc.model() != null ? uc.model().id() : null,
                    uc.model() != null ? uc.model().name() : null,
                    uc.humanReview() != null ? new HumanReviewResponse(uc.humanReview()) : null,
                    uc.killSwitch() != null ? new KillSwitchResponse(uc.killSwitch()) : null,
                    uc.dataReadiness() != null ? new DataReadinessResponse(uc.dataReadiness()) : null,
                    uc.createdAt(), uc.updatedAt()
            );
        }
    }

    public record HumanReviewResponse(
            boolean enabled,
            String trigger,
            String reviewerRole,
            int slaHours,
            boolean requireConsensus,
            int minReviewers,
            EscalationResponse escalation
    ) {
        public HumanReviewResponse(Models.HumanReviewConfig config) {
            this(config.enabled(), config.trigger().name(), config.reviewerRole(),
                 config.slaHours(), config.requireConsensus(), config.minReviewers(),
                 config.escalation() != null ? new EscalationResponse(config.escalation()) : null);
        }
    }

    public record EscalationResponse(int hoursBeforeEscalation, String escalationRole) {
        public EscalationResponse(Models.EscalationConfig config) {
            this(config.hoursBeforeEscalation(), config.escalationRole());
        }
    }

    public record KillSwitchResponse(
            boolean enabled,
            String activationReason,
            String activatedBy,
            Instant activatedAt,
            String scope,
            RollbackResponse rollback
    ) {
        public KillSwitchResponse(Models.KillSwitchConfig config) {
            this(config.enabled(), config.activationReason(), config.activatedBy(),
                 config.activatedAt(), config.scope().name(),
                 config.rollback() != null ? new RollbackResponse(config.rollback()) : null);
        }
    }

    public record RollbackResponse(boolean autoRollback, String targetVersion) {
        public RollbackResponse(Models.RollbackConfig config) {
            this(config.autoRollback(), config.targetVersion());
        }
    }

    public record DataReadinessResponse(
            boolean assessed,
            double completenessScore,
            double qualityScore,
            double biasScore,
            List<String> gaps,
            Instant assessedAt,
            String assessedBy
    ) {
        public DataReadinessResponse(Models.DataReadiness readiness) {
            this(readiness.assessed(), readiness.completenessScore(), readiness.qualityScore(),
                 readiness.biasScore(), readiness.gaps(), readiness.assessedAt(), readiness.assessedBy());
        }
    }

    // Inference API
    public record InferenceRequest(
            UUID useCaseId,
            String correlationId,
            Map<String, Object> input,
            Map<String, String> context
    ) {}

    public record InferenceResponse(
            UUID requestId,
            UUID useCaseId,
            UUID modelId,
            String modelVersion,
            Map<String, Object> output,
            double confidence,
            Map<String, Object> explanation,
            String status,
            Instant completedAt,
            long latencyMs
    ) {
        public static InferenceResponse from(Models.InferenceResponse response) {
            return new InferenceResponse(
                    response.requestId(), response.useCaseId(), response.modelId(),
                    response.modelVersion(), response.output(), response.confidence(),
                    response.explanation(), response.status().name(),
                    response.completedAt(), response.latencyMs()
            );
        }
    }

    // Review API
    public record ReviewDecisionRequest(
            String reviewerId,
            Map<String, Object> decision,
            String notes
    ) {}

    public record ReviewCaseResponse(
            UUID id,
            UUID inferenceRequestId,
            UUID useCaseId,
            String status,
            String assignedReviewer,
            Map<String, Object> inferenceOutput,
            Map<String, Object> reviewerDecision,
            String reviewerNotes,
            Instant createdAt,
            Instant reviewedAt,
            String reviewedBy
    ) {
        public static ReviewCaseResponse from(Models.ReviewCase rc) {
            return new ReviewCaseResponse(
                    rc.id(), rc.inferenceRequestId(), rc.useCaseId(), rc.status(),
                    rc.assignedReviewer(), rc.inferenceOutput(), rc.reviewerDecision(),
                    rc.reviewerNotes(), rc.createdAt(), rc.reviewedAt(), rc.reviewedBy()
            );
        }
    }

    // Drift API
    public record DriftMetricsResponse(
            UUID modelId,
            Instant windowStart,
            Instant windowEnd,
            double psi,
            double featureDriftScore,
            double predictionDriftScore,
            double conceptDriftScore,
            Map<String, Double> featureDriftDetails,
            String status
    ) {
        public static DriftMetricsResponse from(Models.DriftMetrics m) {
            return new DriftMetricsResponse(
                    m.modelId(), m.windowStart(), m.windowEnd(), m.populationStabilityIndex(),
                    m.featureDriftScore(), m.predictionDriftScore(), m.conceptDriftScore(),
                    m.featureDriftDetails(), m.status().name()
            );
        }
    }

    // Bias Test API
    public record BiasTestRequest(
            UUID modelId,
            String protectedAttribute,
            String referenceGroup,
            String comparisonGroup,
            Map<String, Object> testData
    ) {}

    public record BiasTestResponse(
            UUID modelId,
            String protectedAttribute,
            String referenceGroup,
            String comparisonGroup,
            double demographicParityDifference,
            double equalOpportunityDifference,
            double equalizedOddsDifference,
            String status,
            Instant testedAt
    ) {
        public static BiasTestResponse from(Models.BiasTestResult r) {
            return new BiasTestResponse(
                    r.modelId(), r.protectedAttribute(), r.referenceGroup(), r.comparisonGroup(),
                    r.demographicParityDifference(), r.equalOpportunityDifference(),
                    r.equalizedOddsDifference(), r.status().name(), r.testedAt()
            );
        }
    }

    // Adversarial Test API
    public record AdversarialTestRequest(
            UUID modelId,
            String attackType,
            int samplesTested,
            Map<String, Object> testConfig
    ) {}

    public record AdversarialTestResponse(
            UUID modelId,
            String attackType,
            double robustnessScore,
            int samplesTested,
            int samplesFailed,
            String status,
            Instant testedAt
    ) {
        public static AdversarialTestResponse from(Models.AdversarialTestResult r) {
            return new AdversarialTestResponse(
                    r.modelId(), r.attackType(), r.robustnessScore(), r.samplesTested(),
                    r.samplesFailed(), r.status().name(), r.testedAt()
            );
        }
    }

    // Kill Switch API
    public record KillSwitchStateResponse(
            boolean active,
            String reason,
            String activatedBy,
            Instant activatedAt,
            String scope
    ) {
        public static KillSwitchStateResponse from(Ports.KillSwitch.State state) {
            return new KillSwitchStateResponse(
                    state.active(), state.reason(), state.activatedBy(),
                    state.activatedAt(), state.scope().name()
            );
        }
    }

    public record KillSwitchActivateRequest(
            String reason,
            String activatedBy,
            String scope
    ) {}

    // Explanation API
    public record ExplanationResponse(
            UUID inferenceRequestId,
            String method,
            Map<String, Double> featureImportance,
            Map<String, Object> localExplanation,
            String globalExplanationSummary,
            Instant generatedAt
    ) {
        public static ExplanationResponse from(Models.Explanation e) {
            return new ExplanationResponse(
                    e.inferenceRequestId(), e.method().name(), e.featureImportance(),
                    e.localExplanation(), e.globalExplanationSummary(), e.generatedAt()
            );
        }
    }

    // Deployment API
    public record DeployModelRequest(
            UUID modelId,
            String environment
    ) {}

    public record ModelDeploymentResponse(
            UUID id,
            UUID modelId,
            String environment,
            String version,
            String status,
            String deployedBy,
            Instant deployedAt,
            Instant rolledBackAt,
            String rollbackReason
    ) {
        public static ModelDeploymentResponse from(Models.ModelDeployment d) {
            return new ModelDeploymentResponse(
                    d.id(), d.modelId(), d.environment(), d.version(), d.status().name(),
                    d.deployedBy(), d.deployedAt(), d.rolledBackAt(), d.rollbackReason()
            );
        }
    }
}
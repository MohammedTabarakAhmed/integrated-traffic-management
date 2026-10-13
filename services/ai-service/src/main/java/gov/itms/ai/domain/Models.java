package gov.itms.ai.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain models for the AI and agentic automation platform (AI-001..AI-019).
 */
public final class Models {

    private Models() {}

    /**
     * AI Model metadata and configuration.
     */
    public record Model(
            UUID id,
            String name,
            String version,
            String description,
            ModelType type,
            ModelStatus status,
            String owner,
            String framework,
            String artifactUri,
            Map<String, String> hyperparameters,
            Map<String, String> metrics,
            AccuracyTargets accuracyTargets,
            Instant createdAt,
            Instant updatedAt,
            String createdBy
    ) {
        public Model {
            hyperparameters = hyperparameters == null ? Map.of() : Map.copyOf(hyperparameters);
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        }

        public boolean isActive() {
            return status == ModelStatus.ACTIVE;
        }

        public boolean isDeprecated() {
            return status == ModelStatus.DEPRECATED;
        }
    }

    public enum ModelType {
        CLASSIFICATION,
        REGRESSION,
        ANOMALY_DETECTION,
        NLP,
        COMPUTER_VISION,
        ENSEMBLE,
        RULE_BASED
    }

    public enum ModelStatus {
        DRAFT,
        TRAINING,
        VALIDATING,
        ACTIVE,
        DEPRECATED,
        ARCHIVED,
        BLOCKED
    }

    /**
     * Accuracy targets for model validation (AI-004).
     */
    public record AccuracyTargets(
            double minAccuracy,
            double minPrecision,
            double minRecall,
            double minF1Score,
            double maxFalsePositiveRate,
            double maxFalseNegativeRate,
            Map<String, Double> customThresholds
    ) {
        public AccuracyTargets {
            customThresholds = customThresholds == null ? Map.of() : Map.copyOf(customThresholds);
        }

        public static AccuracyTargets defaultTargets() {
            return new AccuracyTargets(0.85, 0.80, 0.80, 0.80, 0.10, 0.10, Map.of());
        }
    }

    /**
     * Use case definition for AI automation (AI-002).
     */
    public record UseCase(
            UUID id,
            String code,
            String name,
            String description,
            List<String> requirementRefs,
            UseCaseStatus status,
            String owner,
            Model model,
            HumanReviewConfig humanReview,
            KillSwitchConfig killSwitch,
            DataReadiness dataReadiness,
            Instant createdAt,
            Instant updatedAt
    ) {
        public boolean requiresHumanReview() {
            return humanReview != null && humanReview.enabled();
        }

        public boolean hasKillSwitch() {
            return killSwitch != null && killSwitch.enabled();
        }
    }

    public enum UseCaseStatus {
        PROPOSED,
        APPROVED,
        IN_DEVELOPMENT,
        IN_TESTING,
        DEPLOYED,
        SUSPENDED,
        RETIRED
    }

    /**
     * Human-in-the-loop review configuration (AI-012, AI-013).
     */
    public record HumanReviewConfig(
            boolean enabled,
            ReviewTrigger trigger,
            String reviewerRole,
            int slaHours,
            boolean requireConsensus,
            int minReviewers,
            EscalationConfig escalation
    ) {
        public HumanReviewConfig {
            escalation = escalation != null ? escalation : new EscalationConfig(24, "AI_ADMIN");
        }
    }

    public enum ReviewTrigger {
        ALWAYS,
        LOW_CONFIDENCE,
        HIGH_RISK,
        RANDOM_SAMPLE,
        ON_DISAGREEMENT
    }

    public record EscalationConfig(
            int hoursBeforeEscalation,
            String escalationRole
    ) {}

    /**
     * Kill switch / emergency stop configuration (AI-014, AI-019).
     */
    public record KillSwitchConfig(
            boolean enabled,
            String activationReason,
            String activatedBy,
            Instant activatedAt,
            KillSwitchScope scope,
            RollbackConfig rollback
    ) {
        public KillSwitchConfig {
            rollback = rollback != null ? rollback : new RollbackConfig(true, "PREVIOUS_STABLE");
        }
    }

    public enum KillSwitchScope {
        USE_CASE,
        MODEL,
        SERVICE_WIDE
    }

    public record RollbackConfig(
            boolean autoRollback,
            String targetVersion
    ) {}

    /**
     * Data readiness assessment (AI-003).
     */
    public record DataReadiness(
            boolean assessed,
            double completenessScore,
            double qualityScore,
            double biasScore,
            List<String> gaps,
            Instant assessedAt,
            String assessedBy
    ) {
        public boolean isReady() {
            return assessed && completenessScore >= 0.8 && qualityScore >= 0.8 && biasScore <= 0.2;
        }
    }

    /**
     * Inference request and response.
     */
    public record InferenceRequest(
            UUID requestId,
            UUID useCaseId,
            String correlationId,
            Map<String, Object> input,
            Map<String, String> context,
            Instant requestedAt,
            String requestedBy
    ) {}

    public record InferenceResponse(
            UUID requestId,
            UUID useCaseId,
            UUID modelId,
            String modelVersion,
            Map<String, Object> output,
            double confidence,
            Map<String, Object> explanation,
            InferenceStatus status,
            Instant completedAt,
            Long latencyMs
    ) {}

    public enum InferenceStatus {
        SUCCESS,
        LOW_CONFIDENCE,
        HUMAN_REVIEW_REQUIRED,
        KILL_SWITCH_ACTIVE,
        MODEL_ERROR,
        VALIDATION_FAILED
    }

    /**
     * Human review case (AI-012, AI-013).
     */
    public record ReviewCase(
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
    ) {}

    /**
     * Drift monitoring metrics (AI-006, AI-007).
     */
    public record DriftMetrics(
            UUID modelId,
            Instant windowStart,
            Instant windowEnd,
            double populationStabilityIndex,
            double featureDriftScore,
            double predictionDriftScore,
            double conceptDriftScore,
            Map<String, Double> featureDriftDetails,
            DriftStatus status
    ) {}

    public enum DriftStatus {
        STABLE,
        WARNING,
        CRITICAL,
        BLOCKED
    }

    /**
     * Bias testing results (AI-015).
     */
    public record BiasTestResult(
            UUID modelId,
            String protectedAttribute,
            String referenceGroup,
            String comparisonGroup,
            double demographicParityDifference,
            double equalOpportunityDifference,
            double equalizedOddsDifference,
            BiasStatus status,
            Instant testedAt
    ) {}

    public enum BiasStatus {
        PASS,
        WARNING,
        FAIL
    }

    /**
     * Adversarial testing results (AI-016).
     */
    public record AdversarialTestResult(
            UUID modelId,
            String attackType,
            double robustnessScore,
            int samplesTested,
            int samplesFailed,
            AdversarialStatus status,
            Instant testedAt
    ) {}

    public enum AdversarialStatus {
        ROBUST,
        VULNERABLE,
        CRITICAL
    }

    /**
     * Model explainability output (AI-008).
     */
    public record Explanation(
            UUID inferenceRequestId,
            ExplanationMethod method,
            Map<String, Double> featureImportance,
            Map<String, Object> localExplanation,
            String globalExplanationSummary,
            Instant generatedAt
    ) {}

    public enum ExplanationMethod {
        SHAP,
        LIME,
        PERMUTATION,
        INTEGRATED_GRADIENTS,
        RULE_BASED
    }

    /**
     * Model versioning and deployment (AI-010, AI-011).
     */
    public record ModelDeployment(
            UUID id,
            UUID modelId,
            String environment,
            String version,
            DeploymentStatus status,
            String deployedBy,
            Instant deployedAt,
            Instant rolledBackAt,
            String rollbackReason
    ) {}

    public enum DeploymentStatus {
        PENDING,
        DEPLOYING,
        ACTIVE,
        ROLLING_BACK,
        ROLLED_BACK,
        FAILED
    }
}
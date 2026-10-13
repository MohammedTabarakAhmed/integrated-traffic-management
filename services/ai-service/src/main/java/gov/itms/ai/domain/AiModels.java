package gov.itms.ai.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AiModels {
    private AiModels() { }

    public record Agent(
            UUID id,
            String code,
            String nameEn,
            String nameAr,
            String descriptionEn,
            String descriptionAr,
            String scope,
            List<String> allowedTools,
            List<String> allowedDataRead,
            List<String> allowedActionsSolo,
            List<String> actionsNeedingApproval,
            String ownerRole,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public record AgentVersion(
            UUID id,
            UUID agentId,
            int version,
            String modelName,
            String modelVersion,
            String promptTemplate,
            Map<String, Object> parameters,
            double accuracyTarget,
            String status,
            Instant createdAt,
            Instant approvedAt,
            String approvedBy
    ) { }

    public record AgentExecution(
            UUID id,
            UUID agentId,
            int agentVersion,
            String goal,
            Map<String, Object> inputData,
            String status,
            List<AgentStep> steps,
            Map<String, Object> outputData,
            String reasoning,
            double confidence,
            boolean humanReviewRequired,
            UUID humanReviewId,
            Instant startedAt,
            Instant completedAt,
            String errorMessage
    ) { }

    public record AgentStep(
            int sequence,
            String toolName,
            Map<String, Object> input,
            Map<String, Object> output,
            Instant startedAt,
            Instant completedAt,
            String status,
            String error
    ) { }

    public record HumanReview(
            UUID id,
            UUID executionId,
            String reviewerRole,
            String status,
            String decision,
            String comments,
            Instant requestedAt,
            Instant decidedAt,
            String decidedBy
    ) { }

    public record KillSwitch(
            UUID id,
            String agentCode,
            String reason,
            String activatedBy,
            Instant activatedAt,
            Instant deactivatedAt,
            String deactivatedBy
    ) { }

    public record UseCase(
            UUID id,
            String code,
            String nameEn,
            String nameAr,
            String descriptionEn,
            String descriptionAr,
            String businessProblem,
            String successCriteria,
            String dataReadiness,
            String assignedAgentCode,
            String status,
            double accuracyTarget,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public record ModelEvaluation(
            UUID id,
            String modelName,
            String modelVersion,
            String useCaseCode,
            Map<String, Double> metrics,
            String testDataset,
            String status,
            Instant evaluatedAt,
            String evaluatedBy
    ) { }

    public record DriftAlert(
            UUID id,
            String modelName,
            String modelVersion,
            String metricName,
            double currentValue,
            double baselineValue,
            double threshold,
            String severity,
            Instant detectedAt,
            String status
    ) { }

    public record AgentAuditLog(
            UUID id,
            UUID agentId,
            UUID executionId,
            String eventType,
            Map<String, Object> details,
            String actorId,
            Instant at
    ) { }

    public enum AgentStatus {
        DRAFT, PENDING_APPROVAL, APPROVED, ACTIVE, SUSPENDED, DEPRECATED, KILLED
    }

    public enum ExecutionStatus {
        PENDING, RUNNING, COMPLETED, FAILED, REQUIRES_REVIEW, REVIEWED, CANCELLED, KILLED
    }

    public enum ReviewStatus {
        PENDING, APPROVED, REJECTED, ESCALATED
    }

    public enum ReviewDecision {
        APPROVE, REJECT, ESCALATE, REQUEST_CHANGES
    }

    public enum KillSwitchScope {
        AGENT, ALL
    }
}
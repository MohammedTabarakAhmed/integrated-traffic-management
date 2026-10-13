package gov.itms.migration.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MigrationModels {
    private MigrationModels() { }

    public record LegacySystem(
            UUID id,
            String code,
            String nameEn,
            String nameAr,
            String descriptionEn,
            String descriptionAr,
            String connectionConfig,
            String entityMappings,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public record EntityMapping(
            UUID id,
            String legacySystemCode,
            String legacyEntityName,
            String targetEntityName,
            String fieldMappings,
            String transformationRules,
            String validationRules,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public record MigrationJob(
            UUID id,
            String code,
            String nameEn,
            String nameAr,
            String legacySystemCode,
            List<String> entityMappings,
            String status,
            Instant scheduledAt,
            Instant startedAt,
            Instant completedAt,
            long totalRecords,
            long processedRecords,
            long successfulRecords,
            long failedRecords,
            String errorSummary,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public record MigrationExecution(
            UUID id,
            UUID jobId,
            String entityName,
            String status,
            long totalRecords,
            long processedRecords,
            long successfulRecords,
            long failedRecords,
            Instant startedAt,
            Instant completedAt,
            String errorDetails
    ) { }

    public record ReconciliationReport(
            UUID id,
            UUID jobId,
            String entityName,
            long sourceCount,
            long targetCount,
            long matchedCount,
            long mismatchedCount,
            long missingInTarget,
            long extraInTarget,
            double matchPercentage,
            String financialReconciliation,
            Instant generatedAt,
            String status
    ) { }

    public record ExceptionRecord(
            UUID id,
            UUID jobId,
            UUID executionId,
            String entityName,
            String legacyId,
            String legacyData,
            String targetId,
            String errorCode,
            String errorMessage,
            String severity,
            String assignedTo,
            String resolution,
            Instant createdAt,
            Instant resolvedAt
    ) { }

    public record RollbackPlan(
            UUID id,
            String jobCode,
            String nameEn,
            String nameAr,
            String descriptionEn,
            String descriptionAr,
            String rollbackSteps,
            String validationQueries,
            boolean tested,
            Instant testedAt,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public record AnonymizationRule(
            UUID id,
            String entityName,
            String fieldName,
            String ruleType,
            String ruleConfig,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public enum JobStatus {
        SCHEDULED, RUNNING, COMPLETED, FAILED, PARTIAL, CANCELLED, ROLLED_BACK
    }

    public enum ExecutionStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public enum ReconciliationStatus {
        PASSED, FAILED, WARNING, PENDING_REVIEW
    }

    public enum ExceptionSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}
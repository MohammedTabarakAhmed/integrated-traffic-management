package gov.itms.migration.api;

import gov.itms.migration.domain.MigrationModels;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() { }

    public record LegacySystemDto(
            UUID id,
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 100) String nameEn,
            @NotBlank @Size(max = 100) String nameAr,
            @Size(max = 500) String descriptionEn,
            @Size(max = 500) String descriptionAr,
            @Size(max = 1000) String connectionConfig,
            @Size(max = 2000) String entityMappings,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public record EntityMappingDto(
            UUID id,
            @NotBlank @Size(max = 40) String legacySystemCode,
            @NotBlank @Size(max = 100) String legacyEntityName,
            @NotBlank @Size(max = 100) String targetEntityName,
            @NotBlank String fieldMappings,
            @Size(max = 2000) String transformationRules,
            @Size(max = 2000) String validationRules,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) {
        public MigrationModels.EntityMapping withLegacySystemCode(String code) {
            return new MigrationModels.EntityMapping(id, code, legacyEntityName, targetEntityName,
                    fieldMappings, transformationRules, validationRules, active, createdAt, updatedAt);
        }
    }

    public record MigrationJobDto(
            UUID id,
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 100) String nameEn,
            @NotBlank @Size(max = 100) String nameAr,
            @NotBlank @Size(max = 40) String legacySystemCode,
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

    public record MigrationExecutionDto(
            UUID id,
            UUID jobId,
            @NotBlank @Size(max = 100) String entityName,
            String status,
            long totalRecords,
            long processedRecords,
            long successfulRecords,
            long failedRecords,
            Instant startedAt,
            Instant completedAt,
            String errorDetails
    ) { }

    public record ReconciliationReportDto(
            UUID id,
            UUID jobId,
            @NotBlank @Size(max = 100) String entityName,
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

    public record ExceptionRecordDto(
            UUID id,
            UUID jobId,
            UUID executionId,
            @NotBlank @Size(max = 100) String entityName,
            @NotBlank String legacyId,
            @NotBlank String legacyData,
            String targetId,
            @NotBlank @Size(max = 40) String errorCode,
            @NotBlank String errorMessage,
            @NotBlank String severity,
            String assignedTo,
            String resolution,
            Instant createdAt,
            Instant resolvedAt
    ) { }

    public record RollbackPlanDto(
            UUID id,
            @NotBlank @Size(max = 40) String jobCode,
            @NotBlank @Size(max = 100) String nameEn,
            @NotBlank @Size(max = 100) String nameAr,
            @Size(max = 500) String descriptionEn,
            @Size(max = 500) String descriptionAr,
            @NotBlank String rollbackSteps,
            @Size(max = 2000) String validationQueries,
            boolean tested,
            Instant testedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        public MigrationModels.RollbackPlan withJobCode(String code) {
            return new MigrationModels.RollbackPlan(id, code, nameEn, nameAr, descriptionEn, descriptionAr,
                    rollbackSteps, validationQueries, tested, testedAt, createdAt, updatedAt);
        }
    }

    public record AnonymizationRuleDto(
            UUID id,
            @NotBlank @Size(max = 100) String entityName,
            @NotBlank @Size(max = 100) String fieldName,
            @NotBlank @Size(max = 40) String ruleType,
            @NotBlank String ruleConfig,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) { }
}
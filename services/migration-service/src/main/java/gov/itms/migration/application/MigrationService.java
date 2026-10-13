package gov.itms.migration.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.migration.domain.DomainException;
import gov.itms.migration.domain.MigrationModels;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MigrationService {
    private final Ports.LegacySystemRepository legacyRepo;
    private final Ports.EntityMappingRepository mappingRepo;
    private final Ports.MigrationJobRepository jobRepo;
    private final Ports.MigrationExecutionRepository execRepo;
    private final Ports.ReconciliationRepository reconRepo;
    private final Ports.ExceptionRepository exceptionRepo;
    private final Ports.RollbackPlanRepository rollbackRepo;
    private final Ports.AnonymizationRepository anonymizationRepo;
    private final Ports.DataAccessPort dataAccess;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;
    private final MigrationProperties properties;

    public MigrationService(Ports.LegacySystemRepository legacyRepo, Ports.EntityMappingRepository mappingRepo,
                            Ports.MigrationJobRepository jobRepo, Ports.MigrationExecutionRepository execRepo,
                            Ports.ReconciliationRepository reconRepo, Ports.ExceptionRepository exceptionRepo,
                            Ports.RollbackPlanRepository rollbackRepo, Ports.AnonymizationRepository anonymizationRepo,
                            Ports.DataAccessPort dataAccess, AuditRecorder audit, DomainEventRecorder events,
                            Clock clock, MigrationProperties properties) {
        this.legacyRepo = legacyRepo;
        this.mappingRepo = mappingRepo;
        this.jobRepo = jobRepo;
        this.execRepo = execRepo;
        this.reconRepo = reconRepo;
        this.exceptionRepo = exceptionRepo;
        this.rollbackRepo = rollbackRepo;
        this.anonymizationRepo = anonymizationRepo;
        this.dataAccess = dataAccess;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.properties = properties;
    }

    // Legacy System Management
    @Transactional(readOnly = true)
    public List<MigrationModels.LegacySystem> listLegacySystems() {
        return legacyRepo.findAllSystems();
    }

    @Transactional
    public MigrationModels.LegacySystem createLegacySystem(MigrationModels.LegacySystem system, CurrentActor actor) {
        if (system.code() == null || system.code().isBlank()) {
            throw DomainException.validation("legacy system code is required", "DAT-001");
        }
        if (legacyRepo.findSystem(system.code()).isPresent()) {
            throw DomainException.conflict("legacy system code already exists", "DAT-001");
        }
        var now = clock.instant();
        var saved = new MigrationModels.LegacySystem(
                UUID.randomUUID(), system.code(), system.nameEn(), system.nameAr(),
                system.descriptionEn(), system.descriptionAr(), system.connectionConfig(),
                system.entityMappings(), system.active(), now, now
        );
        legacyRepo.save(saved);
        audit.record("LEGACY_SYSTEM_CREATED", "LegacySystem", saved.code(), null,
                Map.of("nameEn", saved.nameEn()), null);
        return saved;
    }

    // Entity Mapping
    @Transactional(readOnly = true)
    public List<MigrationModels.EntityMapping> getMappings(String legacySystemCode) {
        return mappingRepo.findByLegacySystem(legacySystemCode);
    }

    @Transactional
    public MigrationModels.EntityMapping createMapping(MigrationModels.EntityMapping mapping, CurrentActor actor) {
        if (mapping.legacySystemCode() == null || mapping.legacySystemCode().isBlank()) {
            throw DomainException.validation("legacy system code is required", "DAT-002");
        }
        var now = clock.instant();
        var saved = new MigrationModels.EntityMapping(
                UUID.randomUUID(), mapping.legacySystemCode(), mapping.legacyEntityName(),
                mapping.targetEntityName(), mapping.fieldMappings(), mapping.transformationRules(),
                mapping.validationRules(), mapping.active(), now, now
        );
        mappingRepo.save(saved);
        audit.record("ENTITY_MAPPING_CREATED", "EntityMapping", saved.id().toString(), null,
                Map.of("legacySystem", saved.legacySystemCode(), "legacyEntity", saved.legacyEntityName()), null);
        return saved;
    }

    // Migration Job
    @Transactional(readOnly = true)
    public List<MigrationModels.MigrationJob> listJobs() {
        return jobRepo.findAllJobs();
    }

    @Transactional(readOnly = true)
    public Optional<MigrationModels.MigrationJob> getJob(String code) {
        return jobRepo.findJob(code);
    }

    @Transactional
    public MigrationModels.MigrationJob createJob(MigrationModels.MigrationJob job, CurrentActor actor) {
        if (job.code() == null || job.code().isBlank()) {
            throw DomainException.validation("job code is required", "DAT-010");
        }
        if (jobRepo.findJob(job.code()).isPresent()) {
            throw DomainException.conflict("job code already exists", "DAT-010");
        }
        var now = clock.instant();
        var saved = new MigrationModels.MigrationJob(
                UUID.randomUUID(), job.code(), job.nameEn(), job.nameAr(),
                job.legacySystemCode(), job.entityMappings(),
                MigrationModels.JobStatus.SCHEDULED.name(),
                job.scheduledAt(), null, null,
                0, 0, 0, 0, null, now, now
        );
        jobRepo.save(saved);
        audit.record("MIGRATION_JOB_CREATED", "MigrationJob", saved.code(), null,
                Map.of("nameEn", saved.nameEn(), "legacySystem", saved.legacySystemCode()), null);
        return saved;
    }

    @Transactional
    public MigrationModels.MigrationJob startJob(String code, CurrentActor actor) {
        var job = jobRepo.findJob(code)
                .orElseThrow(() -> DomainException.notFound("job not found: " + code, "DAT-010"));
        if (!MigrationModels.JobStatus.SCHEDULED.name().equals(job.status())) {
            throw DomainException.validation("job is not in SCHEDULED status", "DAT-010");
        }
        var now = clock.instant();
        var started = new MigrationModels.MigrationJob(
                job.id(), job.code(), job.nameEn(), job.nameAr(), job.legacySystemCode(),
                job.entityMappings(), MigrationModels.JobStatus.RUNNING.name(),
                job.scheduledAt(), now, null,
                job.totalRecords(), job.processedRecords(), job.successfulRecords(),
                job.failedRecords(), job.errorSummary(), job.createdAt(), now
        );
        jobRepo.save(started);

        // Create executions for each entity mapping
        var mappings = mappingRepo.findByLegacySystem(job.legacySystemCode())
                .stream().filter(m -> job.entityMappings().contains(m.legacyEntityName())).toList();
        for (var mapping : mappings) {
            var exec = new MigrationModels.MigrationExecution(
                    UUID.randomUUID(), job.id(), mapping.targetEntityName(),
                    MigrationModels.ExecutionStatus.PENDING.name(),
                    0, 0, 0, 0, null, null, null
            );
            execRepo.save(exec);
        }

        audit.record("MIGRATION_JOB_STARTED", "MigrationJob", code, null, Map.of(), null);
        events.record("itms.migration.job.started", actor.userId(),
                Map.of("jobCode", code, "jobId", job.id().toString()));
        return started;
    }

    @Transactional
    public void completeJob(String code, CurrentActor actor) {
        var job = jobRepo.findJob(code)
                .orElseThrow(() -> DomainException.notFound("job not found: " + code, "DAT-010"));
        var executions = execRepo.findExecutionsByJob(job.id());
        var total = executions.stream().mapToLong(MigrationModels.MigrationExecution::totalRecords).sum();
        var processed = executions.stream().mapToLong(MigrationModels.MigrationExecution::processedRecords).sum();
        var successful = executions.stream().mapToLong(MigrationModels.MigrationExecution::successfulRecords).sum();
        var failed = executions.stream().mapToLong(MigrationModels.MigrationExecution::failedRecords).sum();

        var status = failed == 0 ? MigrationModels.JobStatus.COMPLETED.name() :
                successful == 0 ? MigrationModels.JobStatus.FAILED.name() : MigrationModels.JobStatus.PARTIAL.name();

        var now = clock.instant();
        var completed = new MigrationModels.MigrationJob(
                job.id(), job.code(), job.nameEn(), job.nameAr(), job.legacySystemCode(),
                job.entityMappings(), status,
                job.scheduledAt(), job.startedAt(), now,
                total, processed, successful, failed, null, job.createdAt(), now
        );
        jobRepo.save(completed);

        audit.record("MIGRATION_JOB_COMPLETED", "MigrationJob", code, null,
                Map.of("status", status, "total", total, "successful", successful, "failed", failed), null);
        events.record("itms.migration.job.completed", actor.userId(),
                Map.of("jobCode", code, "status", status, "total", total));
    }

    // Migration Execution
    @Transactional
    public MigrationModels.MigrationExecution startExecution(UUID jobId, String entityName, CurrentActor actor) {
        var executions = execRepo.findExecutionsByJob(jobId);
        var exec = executions.stream()
                .filter(e -> e.entityName().equals(entityName) && e.status().equals(MigrationModels.ExecutionStatus.PENDING.name()))
                .findFirst()
                .orElseThrow(() -> DomainException.notFound("execution not found for entity: " + entityName, "DAT-010"));

        var started = new MigrationModels.MigrationExecution(
                exec.id(), exec.jobId(), exec.entityName(),
                MigrationModels.ExecutionStatus.RUNNING.name(),
                exec.totalRecords(), exec.processedRecords(), exec.successfulRecords(),
                exec.failedRecords(), clock.instant(), null, null
        );
        execRepo.save(started);
        return started;
    }

    @Transactional
    public MigrationModels.MigrationExecution completeExecution(UUID executionId, long total, long processed,
                                                                 long successful, long failed, String errorDetails, CurrentActor actor) {
        var exec = execRepo.findById(executionId)
                .orElseThrow(() -> DomainException.notFound("execution not found: " + executionId, "DAT-010"));

        var completed = new MigrationModels.MigrationExecution(
                exec.id(), exec.jobId(), exec.entityName(),
                failed == 0 ? MigrationModels.ExecutionStatus.COMPLETED.name() : MigrationModels.ExecutionStatus.FAILED.name(),
                total, processed, successful, failed,
                exec.startedAt(), clock.instant(), errorDetails
        );
        execRepo.save(completed);

        // Update job counters
        var job = jobRepo.findJob(jobRepo.findAllJobs().stream()
                .filter(j -> j.id().equals(exec.jobId())).findFirst().map(MigrationModels.MigrationJob::code).orElse(""))
                .orElseThrow();
        var allExecs = execRepo.findExecutionsByJob(job.id());
        var jobTotal = allExecs.stream().mapToLong(MigrationModels.MigrationExecution::totalRecords).sum();
        var jobProcessed = allExecs.stream().mapToLong(MigrationModels.MigrationExecution::processedRecords).sum();
        var jobSuccessful = allExecs.stream().mapToLong(MigrationModels.MigrationExecution::successfulRecords).sum();
        var jobFailed = allExecs.stream().mapToLong(MigrationModels.MigrationExecution::failedRecords).sum();

        var jobStatus = jobFailed == 0 ? MigrationModels.JobStatus.COMPLETED.name() :
                jobSuccessful == 0 ? MigrationModels.JobStatus.FAILED.name() : MigrationModels.JobStatus.PARTIAL.name();

        var updatedJob = new MigrationModels.MigrationJob(
                job.id(), job.code(), job.nameEn(), job.nameAr(), job.legacySystemCode(),
                job.entityMappings(), jobStatus,
                job.scheduledAt(), job.startedAt(), job.completedAt(),
                jobTotal, jobProcessed, jobSuccessful, jobFailed, null, job.createdAt(), clock.instant()
        );
        jobRepo.save(updatedJob);

        return completed;
    }

    // Reconciliation (DAT-010)
    @Transactional
    public MigrationModels.ReconciliationReport reconcile(UUID jobId, String entityName, CurrentActor actor) {
        var job = jobRepo.findAllJobs().stream().filter(j -> j.id().equals(jobId)).findFirst()
                .orElseThrow(() -> DomainException.notFound("job not found", "DAT-010"));

        // In production, this would compare source vs target counts and data
        var report = new MigrationModels.ReconciliationReport(
                UUID.randomUUID(), jobId, entityName,
                0L, 0L, 0L, 0L, 0L, 0L, 0.0, "{}", clock.instant(),
                MigrationModels.ReconciliationStatus.PENDING_REVIEW.name()
        );
        reconRepo.save(report);

        audit.record("RECONCILIATION_GENERATED", "ReconciliationReport", report.id().toString(), null,
                Map.of("jobId", jobId.toString(), "entity", entityName), null);
        return report;
    }

    @Transactional(readOnly = true)
    public List<MigrationModels.ReconciliationReport> getReconciliationReports(UUID jobId) {
        return reconRepo.findReportsByJob(jobId);
    }

    // Exception Store (DAT-011)
    @Transactional
    public MigrationModels.ExceptionRecord recordException(UUID jobId, UUID executionId, String entityName,
                                                            String legacyId, String legacyData, String errorCode,
                                                            String errorMessage, MigrationModels.ExceptionSeverity severity, CurrentActor actor) {
        var exception = new MigrationModels.ExceptionRecord(
                UUID.randomUUID(), jobId, executionId, entityName, legacyId, legacyData,
                null, errorCode, errorMessage, severity.name(), null, null, clock.instant(), null
        );
        exceptionRepo.save(exception);

        audit.record("MIGRATION_EXCEPTION_RECORDED", "ExceptionRecord", exception.id().toString(), null,
                Map.of("jobId", jobId.toString(), "entity", entityName, "severity", severity.name()), null);
        events.record("itms.migration.exception.recorded", actor.userId(),
                Map.of("jobId", jobId.toString(), "entity", entityName, "legacyId", legacyId, "severity", severity.name()));
        return exception;
    }

    @Transactional
    public void resolveException(UUID exceptionId, String resolution, String assignedTo, CurrentActor actor) {
        var exceptions = exceptionRepo.findExceptionsByJob(UUID.fromString("00000000-0000-0000-0000-000000000000")); // placeholder
        var exception = exceptionRepo.findExceptionsByJob(UUID.randomUUID()).stream() // placeholder - would need proper findById
                .filter(e -> e.id().equals(exceptionId)).findFirst()
                .orElseThrow(() -> DomainException.notFound("exception not found: " + exceptionId, "DAT-011"));

        var resolved = new MigrationModels.ExceptionRecord(
                exception.id(), exception.jobId(), exception.executionId(), exception.entityName(),
                exception.legacyId(), exception.legacyData(), exception.targetId(),
                exception.errorCode(), exception.errorMessage(), exception.severity(),
                assignedTo, resolution, exception.createdAt(), clock.instant()
        );
        exceptionRepo.update(resolved);

        audit.record("MIGRATION_EXCEPTION_RESOLVED", "ExceptionRecord", exceptionId.toString(), null,
                Map.of("resolution", resolution), null);
    }

    @Transactional(readOnly = true)
    public List<MigrationModels.ExceptionRecord> getUnresolvedExceptions(UUID jobId) {
        return exceptionRepo.findUnresolved(jobId);
    }

    // Rollback Plan (DAT-013)
    @Transactional
    public MigrationModels.RollbackPlan createRollbackPlan(MigrationModels.RollbackPlan plan, CurrentActor actor) {
        if (plan.jobCode() == null || plan.jobCode().isBlank()) {
            throw DomainException.validation("job code is required for rollback plan", "DAT-013");
        }
        if (rollbackRepo.findByJobCode(plan.jobCode()).isPresent()) {
            throw DomainException.conflict("rollback plan already exists for job: " + plan.jobCode(), "DAT-013");
        }
        var now = clock.instant();
        var saved = new MigrationModels.RollbackPlan(
                UUID.randomUUID(), plan.jobCode(), plan.nameEn(), plan.nameAr(),
                plan.descriptionEn(), plan.descriptionAr(), plan.rollbackSteps(),
                plan.validationQueries(), plan.tested(), plan.testedAt(), now, now
        );
        rollbackRepo.save(saved);
        audit.record("ROLLBACK_PLAN_CREATED", "RollbackPlan", saved.id().toString(), null,
                Map.of("jobCode", saved.jobCode()), null);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<MigrationModels.RollbackPlan> getRollbackPlan(String jobCode) {
        return rollbackRepo.findByJobCode(jobCode);
    }

    // Anonymization (DAT-014)
    @Transactional(readOnly = true)
    public List<MigrationModels.AnonymizationRule> getAnonymizationRules(String entityName) {
        return anonymizationRepo.findByEntity(entityName);
    }

    @Transactional
    public MigrationModels.AnonymizationRule createAnonymizationRule(MigrationModels.AnonymizationRule rule, CurrentActor actor) {
        var now = clock.instant();
        var saved = new MigrationModels.AnonymizationRule(
                UUID.randomUUID(), rule.entityName(), rule.fieldName(),
                rule.ruleType(), rule.ruleConfig(), rule.active(), now, now
        );
        anonymizationRepo.save(saved);
        audit.record("ANONYMIZATION_RULE_CREATED", "AnonymizationRule", saved.id().toString(), null,
                Map.of("entity", saved.entityName(), "field", saved.fieldName()), null);
        return saved;
    }

    @org.springframework.boot.context.properties.ConfigurationProperties("itms.migration")
    public record MigrationProperties(
            int batchSize,
            int maxRetries,
            int retryDelaySeconds,
            String exceptionStorePath
    ) { }
}
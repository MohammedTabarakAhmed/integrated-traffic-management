package gov.itms.migration.api;

import gov.itms.migration.api.ApiModels.LegacySystemDto;
import gov.itms.migration.api.ApiModels.EntityMappingDto;
import gov.itms.migration.api.ApiModels.MigrationJobDto;
import gov.itms.migration.api.ApiModels.MigrationExecutionDto;
import gov.itms.migration.api.ApiModels.ReconciliationReportDto;
import gov.itms.migration.api.ApiModels.ExceptionRecordDto;
import gov.itms.migration.api.ApiModels.RollbackPlanDto;
import gov.itms.migration.api.ApiModels.AnonymizationRuleDto;
import gov.itms.migration.application.MigrationService;
import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.migration.domain.MigrationModels;
import gov.itms.migration.domain.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/migration")
class MigrationController {

    private final MigrationService service;
    private final CurrentActorProvider actors;

    MigrationController(MigrationService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    // Legacy Systems
    @GetMapping("/legacy-systems")
    @PreAuthorize("hasAuthority('migration.legacy.read')")
    List<LegacySystemDto> listLegacySystems() {
        var actor = actors.current();
        return service.listLegacySystems().stream().map(this::toLegacyDto).toList();
    }

    @PostMapping("/legacy-systems")
    @PreAuthorize("hasAuthority('migration.legacy.manage')")
    ResponseEntity<LegacySystemDto> createLegacySystem(@Valid @RequestBody LegacySystemDto request) {
        var actor = actors.current();
        var system = toLegacyDomain(request);
        var saved = service.createLegacySystem(system, actor);
        return ResponseEntity.created(URI.create("/v1/migration/legacy-systems/" + saved.code())).body(toLegacyDto(saved));
    }

    // Entity Mappings
    @GetMapping("/legacy-systems/{code}/mappings")
    @PreAuthorize("hasAuthority('migration.mapping.read')")
    List<EntityMappingDto> getMappings(@PathVariable @NotBlank @Size(max = 40) String code) {
        return service.getMappings(code).stream().map(this::toMappingDto).toList();
    }

    @PostMapping("/legacy-systems/{code}/mappings")
    @PreAuthorize("hasAuthority('migration.mapping.manage')")
    ResponseEntity<EntityMappingDto> createMapping(@PathVariable @NotBlank @Size(max = 40) String code,
                                                    @Valid @RequestBody EntityMappingDto request) {
        var actor = actors.current();
        var mapping = request.withLegacySystemCode(code);
        var saved = service.createMapping(mapping, actor);
        return ResponseEntity.created(URI.create("/v1/migration/legacy-systems/" + code + "/mappings/" + saved.id())).body(toMappingDto(saved));
    }

    // Migration Jobs
    @GetMapping("/jobs")
    @PreAuthorize("hasAuthority('migration.job.read')")
    List<MigrationJobDto> listJobs() {
        var actor = actors.current();
        return service.listJobs().stream().map(this::toJobDto).toList();
    }

    @GetMapping("/jobs/{code}")
    @PreAuthorize("hasAuthority('migration.job.read')")
    MigrationJobDto getJob(@PathVariable @NotBlank @Size(max = 40) String code) {
        var actor = actors.current();
        return service.getJob(code)
                .map(this::toJobDto)
                .orElseThrow(() -> DomainException.notFound("job not found", "DAT-010"));
    }

    @PostMapping("/jobs")
    @PreAuthorize("hasAuthority('migration.job.manage')")
    ResponseEntity<MigrationJobDto> createJob(@Valid @RequestBody MigrationJobDto request) {
        var actor = actors.current();
        var job = toJobDomain(request);
        var saved = service.createJob(job, actor);
        return ResponseEntity.created(URI.create("/v1/migration/jobs/" + saved.code())).body(toJobDto(saved));
    }

    @PostMapping("/jobs/{code}/start")
    @PreAuthorize("hasAuthority('migration.job.execute')")
    MigrationJobDto startJob(@PathVariable @NotBlank @Size(max = 40) String code) {
        var actor = actors.current();
        return toJobDto(service.startJob(code, actor));
    }

    @PostMapping("/jobs/{code}/complete")
    @PreAuthorize("hasAuthority('migration.job.execute')")
    MigrationJobDto completeJob(@PathVariable @NotBlank @Size(max = 40) String code) {
        var actor = actors.current();
        service.completeJob(code, actor);
        return getJob(code);
    }

    // Migration Executions
    @PostMapping("/jobs/{jobCode}/executions/{entityName}/start")
    @PreAuthorize("hasAuthority('migration.job.execute')")
    MigrationExecutionDto startExecution(@PathVariable @NotBlank @Size(max = 40) String jobCode,
                                          @PathVariable @NotBlank @Size(max = 100) String entityName) {
        var actor = actors.current();
        var job = service.getJob(jobCode)
                .orElseThrow(() -> DomainException.notFound("job not found", "DAT-010"));
        var exec = service.startExecution(job.id(), entityName, actor);
        return toExecutionDto(exec);
    }

    @PostMapping("/executions/{id}/complete")
    @PreAuthorize("hasAuthority('migration.job.execute')")
    MigrationExecutionDto completeExecution(@PathVariable UUID id,
                                             @RequestParam long total,
                                             @RequestParam long processed,
                                             @RequestParam long successful,
                                             @RequestParam long failed,
                                             @RequestParam(required = false) String errorDetails) {
        var actor = actors.current();
        return toExecutionDto(service.completeExecution(id, total, processed, successful, failed, errorDetails, actor));
    }

    // Reconciliation
    @PostMapping("/jobs/{jobCode}/reconcile/{entityName}")
    @PreAuthorize("hasAuthority('migration.reconcile.execute')")
    ReconciliationReportDto reconcile(@PathVariable @NotBlank @Size(max = 40) String jobCode,
                                       @PathVariable @NotBlank @Size(max = 100) String entityName) {
        var actor = actors.current();
        var job = service.getJob(jobCode)
                .orElseThrow(() -> DomainException.notFound("job not found", "DAT-010"));
        return toReconciliationDto(service.reconcile(job.id(), entityName, actor));
    }

    @GetMapping("/jobs/{jobCode}/reconciliation")
    @PreAuthorize("hasAuthority('migration.reconcile.read')")
    List<ReconciliationReportDto> getReconciliation(@PathVariable @NotBlank @Size(max = 40) String jobCode) {
        var job = service.getJob(jobCode)
                .orElseThrow(() -> DomainException.notFound("job not found", "DAT-010"));
        return service.getReconciliationReports(job.id()).stream().map(this::toReconciliationDto).toList();
    }

    // Exceptions
    @PostMapping("/jobs/{jobCode}/executions/{executionId}/exceptions")
    @PreAuthorize("hasAuthority('migration.exception.record')")
    ExceptionRecordDto recordException(@PathVariable @NotBlank @Size(max = 40) String jobCode,
                                        @PathVariable UUID executionId,
                                        @RequestParam String entityName,
                                        @RequestParam String legacyId,
                                        @RequestParam String legacyData,
                                        @RequestParam String errorCode,
                                        @RequestParam String errorMessage,
                                        @RequestParam String severity) {
        var actor = actors.current();
        var job = service.getJob(jobCode)
                .orElseThrow(() -> DomainException.notFound("job not found", "DAT-010"));
        return toExceptionDto(service.recordException(job.id(), executionId, entityName, legacyId, legacyData,
                errorCode, errorMessage, MigrationModels.ExceptionSeverity.valueOf(severity.toUpperCase()), actor));
    }

    @PostMapping("/exceptions/{id}/resolve")
    @PreAuthorize("hasAuthority('migration.exception.resolve')")
    ExceptionRecordDto resolveException(@PathVariable UUID id,
                                         @RequestParam String resolution,
                                         @RequestParam String assignedTo) {
        var actor = actors.current();
        service.resolveException(id, resolution, assignedTo, actor);
        // Would need a findById in service to return updated
        return new ExceptionRecordDto(id, null, null, null, null, null, null, null, null, null, assignedTo, resolution, null, null);
    }

    @GetMapping("/jobs/{jobCode}/exceptions/unresolved")
    @PreAuthorize("hasAuthority('migration.exception.read')")
    List<ExceptionRecordDto> getUnresolvedExceptions(@PathVariable @NotBlank @Size(max = 40) String jobCode) {
        var job = service.getJob(jobCode)
                .orElseThrow(() -> DomainException.notFound("job not found", "DAT-010"));
        return service.getUnresolvedExceptions(job.id()).stream().map(this::toExceptionDto).toList();
    }

    // Rollback Plans
    @PostMapping("/jobs/{jobCode}/rollback-plan")
    @PreAuthorize("hasAuthority('migration.rollback.manage')")
    ResponseEntity<RollbackPlanDto> createRollbackPlan(@PathVariable @NotBlank @Size(max = 40) String jobCode,
                                                        @Valid @RequestBody RollbackPlanDto request) {
        var actor = actors.current();
        var plan = request.withJobCode(jobCode);
        var saved = service.createRollbackPlan(plan, actor);
        return ResponseEntity.created(URI.create("/v1/migration/jobs/" + jobCode + "/rollback-plan")).body(toRollbackDto(saved));
    }

    @GetMapping("/jobs/{jobCode}/rollback-plan")
    @PreAuthorize("hasAuthority('migration.rollback.read')")
    RollbackPlanDto getRollbackPlan(@PathVariable @NotBlank @Size(max = 40) String jobCode) {
        return service.getRollbackPlan(jobCode)
                .map(this::toRollbackDto)
                .orElseThrow(() -> DomainException.notFound("rollback plan not found", "DAT-013"));
    }

    // Anonymization
    @GetMapping("/anonymization/{entityName}")
    @PreAuthorize("hasAuthority('migration.anonymization.read')")
    List<AnonymizationRuleDto> getAnonymizationRules(@PathVariable @NotBlank @Size(max = 100) String entityName) {
        return service.getAnonymizationRules(entityName).stream().map(this::toAnonymizationDto).toList();
    }

    @PostMapping("/anonymization")
    @PreAuthorize("hasAuthority('migration.anonymization.manage')")
    ResponseEntity<AnonymizationRuleDto> createAnonymizationRule(@Valid @RequestBody AnonymizationRuleDto request) {
        var actor = actors.current();
        var rule = toAnonymizationDomain(request);
        var saved = service.createAnonymizationRule(rule, actor);
        return ResponseEntity.created(URI.create("/v1/migration/anonymization/" + saved.entityName() + "/" + saved.fieldName())).body(toAnonymizationDto(saved));
    }

    private LegacySystemDto toLegacyDto(MigrationModels.LegacySystem s) {
        return new LegacySystemDto(s.id(), s.code(), s.nameEn(), s.nameAr(), s.descriptionEn(), s.descriptionAr(),
                s.connectionConfig(), s.entityMappings(), s.active(), s.createdAt(), s.updatedAt());
    }

    private MigrationModels.LegacySystem toLegacyDomain(LegacySystemDto dto) {
        return new MigrationModels.LegacySystem(null, dto.code(), dto.nameEn(), dto.nameAr(),
                dto.descriptionEn(), dto.descriptionAr(), dto.connectionConfig(), dto.entityMappings(),
                dto.active(), null, null);
    }

    private EntityMappingDto toMappingDto(MigrationModels.EntityMapping m) {
        return new EntityMappingDto(m.id(), m.legacySystemCode(), m.legacyEntityName(), m.targetEntityName(),
                m.fieldMappings(), m.transformationRules(), m.validationRules(), m.active(), m.createdAt(), m.updatedAt());
    }

    private MigrationModels.EntityMapping toMappingDomain(EntityMappingDto dto) {
        return new MigrationModels.EntityMapping(null, dto.legacySystemCode(), dto.legacyEntityName(),
                dto.targetEntityName(), dto.fieldMappings(), dto.transformationRules(), dto.validationRules(),
                dto.active(), null, null);
    }

    private MigrationJobDto toJobDto(MigrationModels.MigrationJob j) {
        return new MigrationJobDto(j.id(), j.code(), j.nameEn(), j.nameAr(), j.legacySystemCode(),
                j.entityMappings(), j.status(), j.scheduledAt(), j.startedAt(), j.completedAt(),
                j.totalRecords(), j.processedRecords(), j.successfulRecords(), j.failedRecords(),
                j.errorSummary(), j.createdAt(), j.updatedAt());
    }

    private MigrationModels.MigrationJob toJobDomain(MigrationJobDto dto) {
        return new MigrationModels.MigrationJob(null, dto.code(), dto.nameEn(), dto.nameAr(),
                dto.legacySystemCode(), dto.entityMappings(), null, dto.scheduledAt(), null, null,
                0, 0, 0, 0, null, null, null);
    }

    private MigrationExecutionDto toExecutionDto(MigrationModels.MigrationExecution e) {
        return new MigrationExecutionDto(e.id(), e.jobId(), e.entityName(), e.status(),
                e.totalRecords(), e.processedRecords(), e.successfulRecords(), e.failedRecords(),
                e.startedAt(), e.completedAt(), e.errorDetails());
    }

    private ReconciliationReportDto toReconciliationDto(MigrationModels.ReconciliationReport r) {
        return new ReconciliationReportDto(r.id(), r.jobId(), r.entityName(), r.sourceCount(), r.targetCount(),
                r.matchedCount(), r.mismatchedCount(), r.missingInTarget(), r.extraInTarget(),
                r.matchPercentage(), r.financialReconciliation(), r.generatedAt(), r.status());
    }

    private ExceptionRecordDto toExceptionDto(MigrationModels.ExceptionRecord e) {
        return new ExceptionRecordDto(e.id(), e.jobId(), e.executionId(), e.entityName(), e.legacyId(),
                e.legacyData(), e.targetId(), e.errorCode(), e.errorMessage(), e.severity(),
                e.assignedTo(), e.resolution(), e.createdAt(), e.resolvedAt());
    }

    private RollbackPlanDto toRollbackDto(MigrationModels.RollbackPlan r) {
        return new RollbackPlanDto(r.id(), r.jobCode(), r.nameEn(), r.nameAr(), r.descriptionEn(),
                r.descriptionAr(), r.rollbackSteps(), r.validationQueries(), r.tested(), r.testedAt(),
                r.createdAt(), r.updatedAt());
    }

    private MigrationModels.RollbackPlan toRollbackDomain(RollbackPlanDto dto) {
        return new MigrationModels.RollbackPlan(null, dto.jobCode(), dto.nameEn(), dto.nameAr(),
                dto.descriptionEn(), dto.descriptionAr(), dto.rollbackSteps(), dto.validationQueries(),
                dto.tested(), dto.testedAt(), null, null);
    }

    private AnonymizationRuleDto toAnonymizationDto(MigrationModels.AnonymizationRule r) {
        return new AnonymizationRuleDto(r.id(), r.entityName(), r.fieldName(), r.ruleType(),
                r.ruleConfig(), r.active(), r.createdAt(), r.updatedAt());
    }

    private MigrationModels.AnonymizationRule toAnonymizationDomain(AnonymizationRuleDto dto) {
        return new MigrationModels.AnonymizationRule(null, dto.entityName(), dto.fieldName(),
                dto.ruleType(), dto.ruleConfig(), dto.active(), null, null);
    }
}
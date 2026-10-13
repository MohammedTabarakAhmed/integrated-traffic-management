package gov.itms.ai.application;

import gov.itms.ai.domain.Models;
import gov.itms.ai.domain.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case management for AI automation (AI-002, AI-003, AI-012, AI-013, AI-014, AI-019).
 */
public final class UseCaseService {

    private final Ports.UseCaseRepository useCaseRepository;
    private final Ports.ModelRepository modelRepository;
    private final Ports.KillSwitch killSwitch;

    public UseCaseService(Ports.UseCaseRepository useCaseRepository,
                          Ports.ModelRepository modelRepository,
                          Ports.KillSwitch killSwitch) {
        this.useCaseRepository = useCaseRepository;
        this.modelRepository = modelRepository;
        this.killSwitch = killSwitch;
    }

    /** Register a new AI use case (AI-002). */
    public Models.UseCase registerUseCase(String code, String name, String description,
                                           List<String> requirementRefs, String owner,
                                           Models.HumanReviewConfig humanReview,
                                           Models.KillSwitchConfig killSwitchConfig,
                                           Models.DataReadiness dataReadiness,
                                           String createdBy) {
        if (useCaseRepository.findByCode(code).isPresent()) {
            throw DomainException.conflict("USE_CASE_CODE_EXISTS",
                    "Use case with code %s already exists".formatted(code),
                    List.of("AI-002"), code);
        }

        Models.UseCase useCase = new Models.UseCase(
                UUID.randomUUID(),
                code,
                name,
                description,
                requirementRefs,
                Models.UseCaseStatus.PROPOSED,
                owner,
                null, // model assigned later
                humanReview,
                killSwitchConfig,
                dataReadiness,
                Instant.now(),
                Instant.now()
        );

        useCaseRepository.insertUseCase(useCase);
        return useCase;
    }

    /** Assign model to use case. */
    public Models.UseCase assignModel(UUID useCaseId, UUID modelId, String assignedBy) {
        Models.UseCase useCase = useCaseRepository.findById(useCaseId)
                .orElseThrow(() -> DomainException.notFound("USE_CASE_NOT_FOUND",
                        "Use case %s not found".formatted(useCaseId), List.of("AI-002"), useCaseId));

        Models.Model model = modelRepository.findById(modelId)
                .orElseThrow(() -> DomainException.notFound("MODEL_NOT_FOUND",
                        "Model %s not found".formatted(modelId), List.of("AI-001"), modelId));

        if (model.status() != Models.ModelStatus.ACTIVE) {
            throw DomainException.validation("MODEL_NOT_ACTIVE",
                    "Only ACTIVE models can be assigned to use cases",
                    List.of("AI-002"), model.status());
        }

        Models.UseCase updated = new Models.UseCase(
                useCase.id(), useCase.code(), useCase.name(), useCase.description(),
                useCase.requirementRefs(), useCase.status(), useCase.owner(),
                model, useCase.humanReview(), useCase.killSwitch(), useCase.dataReadiness(),
                useCase.createdAt(), Instant.now()
        );

        useCaseRepository.updateUseCase(updated);
        return updated;
    }

    /** Approve use case for deployment (AI-002). */
    public Models.UseCase approveUseCase(UUID useCaseId, String approvedBy) {
        Models.UseCase useCase = useCaseRepository.findById(useCaseId)
                .orElseThrow(() -> DomainException.notFound("USE_CASE_NOT_FOUND",
                        "Use case %s not found".formatted(useCaseId), List.of("AI-002"), useCaseId));

        if (useCase.model() == null) {
            throw DomainException.validation("NO_MODEL_ASSIGNED",
                    "Use case must have an assigned model before approval",
                    List.of("AI-002"));
        }

        if (!useCase.dataReadiness().isReady()) {
            throw DomainException.validation("DATA_NOT_READY",
                    "Data readiness assessment must pass before approval",
                    List.of("AI-003"), useCase.dataReadiness().gaps());
        }

        Models.UseCase updated = new Models.UseCase(
                useCase.id(), useCase.code(), useCase.name(), useCase.description(),
                useCase.requirementRefs(), Models.UseCaseStatus.APPROVED,
                useCase.owner(), useCase.model(), useCase.humanReview(),
                useCase.killSwitch(), useCase.dataReadiness(),
                useCase.createdAt(), Instant.now()
        );

        useCaseRepository.updateUseCase(updated);
        return updated;
    }

    /** Deploy use case (AI-010). */
    public Models.UseCase deployUseCase(UUID useCaseId, String deployedBy) {
        Models.UseCase useCase = useCaseRepository.findById(useCaseId)
                .orElseThrow(() -> DomainException.notFound("USE_CASE_NOT_FOUND",
                        "Use case %s not found".formatted(useCaseId), List.of("AI-002"), useCaseId));

        if (useCase.status() != Models.UseCaseStatus.APPROVED) {
            throw DomainException.validation("USE_CASE_NOT_APPROVED",
                    "Only APPROVED use cases can be deployed",
                    List.of("AI-002"), useCase.status());
        }

        Models.UseCase updated = new Models.UseCase(
                useCase.id(), useCase.code(), useCase.name(), useCase.description(),
                useCase.requirementRefs(), Models.UseCaseStatus.DEPLOYED,
                useCase.owner(), useCase.model(), useCase.humanReview(),
                useCase.killSwitch(), useCase.dataReadiness(),
                useCase.createdAt(), Instant.now()
        );

        useCaseRepository.updateUseCase(updated);
        return updated;
    }

    /** Activate kill switch for use case (AI-014, AI-019). */
    public void activateKillSwitch(UUID useCaseId, String reason, String activatedBy) {
        Models.UseCase useCase = useCaseRepository.findById(useCaseId)
                .orElseThrow(() -> DomainException.notFound("USE_CASE_NOT_FOUND",
                        "Use case %s not found".formatted(useCaseId), List.of("AI-014"), useCaseId));

        if (useCase.killSwitch() == null || !useCase.killSwitch().enabled()) {
            throw DomainException.validation("KILL_SWITCH_NOT_CONFIGURED",
                    "Kill switch not configured for this use case",
                    List.of("AI-014"));
        }

        killSwitch.activate(reason, activatedBy, Models.KillSwitchScope.USE_CASE);

        Models.UseCase updated = new Models.UseCase(
                useCase.id(), useCase.code(), useCase.name(), useCase.description(),
                useCase.requirementRefs(), Models.UseCaseStatus.SUSPENDED,
                useCase.owner(), useCase.model(), useCase.humanReview(),
                new Models.KillSwitchConfig(true, reason, activatedBy, Instant.now(),
                        Models.KillSwitchScope.USE_CASE, useCase.killSwitch().rollback()),
                useCase.dataReadiness(), useCase.createdAt(), Instant.now()
        );

        useCaseRepository.updateUseCase(updated);
    }

    /** Deactivate kill switch (AI-019). */
    public void deactivateKillSwitch(UUID useCaseId, String deactivatedBy) {
        Models.UseCase useCase = useCaseRepository.findById(useCaseId)
                .orElseThrow(() -> DomainException.notFound("USE_CASE_NOT_FOUND",
                        "Use case %s not found".formatted(useCaseId), List.of("AI-019"), useCaseId));

        killSwitch.deactivate(deactivatedBy);

        Models.UseCase updated = new Models.UseCase(
                useCase.id(), useCase.code(), useCase.name(), useCase.description(),
                useCase.requirementRefs(), Models.UseCaseStatus.DEPLOYED,
                useCase.owner(), useCase.model(), useCase.humanReview(),
                new Models.KillSwitchConfig(false, null, null, null,
                        Models.KillSwitchScope.USE_CASE, useCase.killSwitch().rollback()),
                useCase.dataReadiness(), useCase.createdAt(), Instant.now()
        );

        useCaseRepository.updateUseCase(updated);
    }

    /** Update data readiness assessment (AI-003). */
    public Models.UseCase updateDataReadiness(UUID useCaseId, Models.DataReadiness readiness, String assessedBy) {
        Models.UseCase useCase = useCaseRepository.findById(useCaseId)
                .orElseThrow(() -> DomainException.notFound("USE_CASE_NOT_FOUND",
                        "Use case %s not found".formatted(useCaseId), List.of("AI-003"), useCaseId));

        Models.DataReadiness updatedReadiness = new Models.DataReadiness(
                true, readiness.completenessScore(), readiness.qualityScore(),
                readiness.biasScore(), readiness.gaps(), Instant.now(), assessedBy
        );

        Models.UseCase updated = new Models.UseCase(
                useCase.id(), useCase.code(), useCase.name(), useCase.description(),
                useCase.requirementRefs(), useCase.status(), useCase.owner(),
                useCase.model(), useCase.humanReview(), useCase.killSwitch(),
                updatedReadiness, useCase.createdAt(), Instant.now()
        );

        useCaseRepository.updateUseCase(updated);
        return updated;
    }

    public Optional<Models.UseCase> findById(UUID id) {
        return useCaseRepository.findById(id);
    }

    public Optional<Models.UseCase> findByCode(String code) {
        return useCaseRepository.findByCode(code);
    }

    public List<Models.UseCase> findByStatus(Models.UseCaseStatus status) {
        return useCaseRepository.findByStatus(status);
    }

    public List<Models.UseCase> findAll(int limit, int offset) {
        return useCaseRepository.findAll(limit, offset);
    }
}
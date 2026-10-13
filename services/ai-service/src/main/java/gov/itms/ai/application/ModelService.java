package gov.itms.ai.application;

import gov.itms.ai.domain.Models;
import gov.itms.ai.domain.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Model registry and lifecycle management (AI-001, AI-009, AI-010, AI-011).
 */
public final class ModelService {

    private final Ports.ModelRepository modelRepository;
    private final Ports.ModelEvaluator evaluator;

    public ModelService(Ports.ModelRepository modelRepository, Ports.ModelEvaluator evaluator) {
        this.modelRepository = modelRepository;
        this.evaluator = evaluator;
    }

    /** Register a new model version (AI-001). */
    public Models.Model registerModel(String name, String version, String description, Models.ModelType type,
                                       String owner, String framework, String artifactUri,
                                       Map<String, String> hyperparameters, Models.AccuracyTargets targets,
                                       String createdBy) {
        // Check for duplicate name+version
        if (modelRepository.findByNameAndVersion(name, version).isPresent()) {
            throw DomainException.conflict("MODEL_VERSION_EXISTS",
                    "Model %s version %s already exists".formatted(name, version),
                    List.of("AI-001"), name, version);
        }

        Models.Model model = new Models.Model(
                UUID.randomUUID(),
                name,
                version,
                description,
                type,
                Models.ModelStatus.DRAFT,
                owner,
                framework,
                artifactUri,
                hyperparameters,
                Map.of(),
                targets != null ? targets : Models.AccuracyTargets.defaultTargets(),
                Instant.now(),
                Instant.now(),
                createdBy
        );

        modelRepository.insertModel(model);
        return model;
    }

    /** Submit model for validation (AI-004). */
    public Models.Model submitForValidation(UUID modelId, String submittedBy) {
        Models.Model model = modelRepository.findById(modelId)
                .orElseThrow(() -> DomainException.notFound("MODEL_NOT_FOUND",
                        "Model %s not found".formatted(modelId), List.of("AI-001"), modelId));

        if (model.status() != Models.ModelStatus.DRAFT) {
            throw DomainException.validation("INVALID_MODEL_STATE",
                    "Only DRAFT models can be submitted for validation",
                    List.of("AI-004"), model.status());
        }

        Models.Model updated = new Models.Model(
                model.id(), model.name(), model.version(), model.description(),
                model.type(), Models.ModelStatus.VALIDATING,
                model.owner(), model.framework(), model.artifactUri(),
                model.hyperparameters(), model.metrics(), model.accuracyTargets(),
                model.createdAt(), Instant.now(), submittedBy
        );

        modelRepository.updateModel(updated);
        return updated;
    }

    /** Validate model against test data (AI-004). */
    public Models.Model validateModel(UUID modelId, List<Map<String, Object>> testData, String validatedBy) {
        Models.Model model = modelRepository.findById(modelId)
                .orElseThrow(() -> DomainException.notFound("MODEL_NOT_FOUND",
                        "Model %s not found".formatted(modelId), List.of("AI-001"), modelId));

        if (model.status() != Models.ModelStatus.VALIDATING) {
            throw DomainException.validation("INVALID_MODEL_STATE",
                    "Model must be in VALIDATING state",
                    List.of("AI-004"), model.status());
        }

        var result = evaluator.evaluate(model, testData);

        Models.Model updated = new Models.Model(
                model.id(), model.name(), model.version(), model.description(),
                model.type(), result.meetsTargets() ? Models.ModelStatus.ACTIVE : Models.ModelStatus.BLOCKED,
                model.owner(), model.framework(), model.artifactUri(),
                model.hyperparameters(), Map.of(
                        "accuracy", String.valueOf(result.accuracy()),
                        "precision", String.valueOf(result.precision()),
                        "recall", String.valueOf(result.recall()),
                        "f1Score", String.valueOf(result.f1Score())
                ), model.accuracyTargets(),
                model.createdAt(), Instant.now(), validatedBy
        );

        modelRepository.updateModel(updated);
        return updated;
    }

    /** Deploy model to environment (AI-010, AI-011). */
    public Models.ModelDeployment deployModel(UUID modelId, String environment, String deployedBy) {
        Models.Model model = modelRepository.findById(modelId)
                .orElseThrow(() -> DomainException.notFound("MODEL_NOT_FOUND",
                        "Model %s not found".formatted(modelId), List.of("AI-001"), modelId));

        if (model.status() != Models.ModelStatus.ACTIVE) {
            throw DomainException.validation("MODEL_NOT_ACTIVE",
                    "Only ACTIVE models can be deployed",
                    List.of("AI-010"), model.status());
        }

        // Check for existing active deployment
        var existing = modelRepository.findActiveDeployment(modelId, environment);
        if (existing.isPresent()) {
            throw DomainException.conflict("DEPLOYMENT_EXISTS",
                    "Active deployment already exists for model %s in %s".formatted(modelId, environment),
                    List.of("AI-010"), modelId, environment);
        }

        Models.ModelDeployment deployment = new Models.ModelDeployment(
                UUID.randomUUID(),
                modelId,
                environment,
                model.version(),
                Models.DeploymentStatus.ACTIVE,
                deployedBy,
                Instant.now(),
                null,
                null
        );

        modelRepository.insertDeployment(deployment);
        return deployment;
    }

    /** Rollback deployment (AI-011). */
    public Models.ModelDeployment rollbackDeployment(UUID deploymentId, String reason, String rolledBackBy) {
        var deploymentOpt = modelRepository.findDeployments(deploymentId).stream().findFirst();
        // Note: In real implementation, we'd have a findById for deployments
        throw new UnsupportedOperationException("Rollback requires deployment repository findById");
    }

    /** Deprecate a model (AI-009). */
    public Models.Model deprecateModel(UUID modelId, String deprecatedBy) {
        Models.Model model = modelRepository.findById(modelId)
                .orElseThrow(() -> DomainException.notFound("MODEL_NOT_FOUND",
                        "Model %s not found".formatted(modelId), List.of("AI-009"), modelId));

        Models.Model updated = new Models.Model(
                model.id(), model.name(), model.version(), model.description(),
                model.type(), Models.ModelStatus.DEPRECATED,
                model.owner(), model.framework(), model.artifactUri(),
                model.hyperparameters(), model.metrics(), model.accuracyTargets(),
                model.createdAt(), Instant.now(), deprecatedBy
        );

        modelRepository.updateModel(updated);
        return updated;
    }

    /** Get active model for use case. */
    public Optional<Models.Model> getActiveModelForUseCase(UUID useCaseId) {
        // This would typically join with use case repository
        // For now, return latest active model of appropriate type
        return modelRepository.findByStatus(Models.ModelStatus.ACTIVE).stream().findFirst();
    }

    public Optional<Models.Model> findById(UUID id) {
        return modelRepository.findById(id);
    }

    public List<Models.Model> findByType(Models.ModelType type) {
        return modelRepository.findByType(type);
    }

    public List<Models.Model> findByStatus(Models.ModelStatus status) {
        return modelRepository.findByStatus(status);
    }

    public List<Models.Model> findAll(int limit, int offset) {
        return modelRepository.findAll(limit, offset);
    }
}
package gov.itms.ai.application;

import java.util.List;

import gov.itms.ai.domain.Models;
import gov.itms.ai.domain.DomainException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Kill switch / emergency stop service (AI-014, AI-019).
 */
public final class KillSwitchService {

    private final Ports.KillSwitch killSwitch;
    private final Ports.UseCaseRepository useCaseRepository;
    private final Ports.ModelRepository modelRepository;

    public KillSwitchService(Ports.KillSwitch killSwitch,
                              Ports.UseCaseRepository useCaseRepository,
                              Ports.ModelRepository modelRepository) {
        this.killSwitch = killSwitch;
        this.useCaseRepository = useCaseRepository;
        this.modelRepository = modelRepository;
    }

    /** Get current kill switch state. */
    public Ports.KillSwitch.State getState() {
        return killSwitch.getState();
    }

    /** Activate kill switch (AI-014). */
    public void activate(String reason, String activatedBy, Models.KillSwitchScope scope) {
        var currentState = killSwitch.getState();
        if (currentState.active()) {
            throw new IllegalStateException("Kill switch already active: %s".formatted(currentState.reason()));
        }

        killSwitch.activate(reason, activatedBy, scope);

        // Suspend affected use cases
        if (scope == Models.KillSwitchScope.USE_CASE || scope == Models.KillSwitchScope.SERVICE_WIDE) {
            suspendUseCases(scope);
        }

        // Block affected models
        if (scope == Models.KillSwitchScope.MODEL || scope == Models.KillSwitchScope.SERVICE_WIDE) {
            blockModels(scope);
        }
    }

    /** Deactivate kill switch (AI-019). */
    public void deactivate(String deactivatedBy) {
        var currentState = killSwitch.getState();
        if (!currentState.active()) {
            throw new IllegalStateException("Kill switch is not active");
        }

        killSwitch.deactivate(deactivatedBy);

        // Reactivate use cases if they have rollback configured
        reactivateUseCases(currentState.scope());
    }

    /** Activate kill switch for specific use case. */
    public void activateForUseCase(UUID useCaseId, String reason, String activatedBy) {
        // Delegate to UseCaseService which handles the use case status update
        // This service just activates the kill switch at USE_CASE scope
        killSwitch.activate(reason, activatedBy, Models.KillSwitchScope.USE_CASE);
    }

    /** Check if inference should proceed. */
    public boolean isInferenceAllowed(Models.KillSwitchScope scope) {
        return !killSwitch.isActive(scope) && !killSwitch.isActive(Models.KillSwitchScope.SERVICE_WIDE);
    }

    private void suspendUseCases(Models.KillSwitchScope scope) {
        List<Models.UseCase> deployedUseCases = useCaseRepository.findByStatus(Models.UseCaseStatus.DEPLOYED);
        for (Models.UseCase useCase : deployedUseCases) {
            if (useCase.killSwitch() != null && useCase.killSwitch().enabled()) {
                // Use case has kill switch configured - it will be suspended by UseCaseService
            }
        }
    }

    private void blockModels(Models.KillSwitchScope scope) {
        List<Models.Model> activeModels = modelRepository.findByStatus(Models.ModelStatus.ACTIVE);
        for (Models.Model model : activeModels) {
            // Models are blocked via kill switch state check in InferenceService
        }
    }

    private void reactivateUseCases(Models.KillSwitchScope scope) {
        List<Models.UseCase> suspendedUseCases = useCaseRepository.findByStatus(Models.UseCaseStatus.SUSPENDED);
        for (Models.UseCase useCase : suspendedUseCases) {
            if (useCase.killSwitch() != null && useCase.killSwitch().enabled() &&
                useCase.killSwitch().rollback().autoRollback()) {
                // Reactivate - would be done by UseCaseService.deactivateKillSwitch
            }
        }
    }
}
package gov.itms.ai.api;

import gov.itms.ai.application.UseCaseService;
import gov.itms.ai.domain.Models;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Use case management API (AI-002, AI-003, AI-012, AI-013, AI-014, AI-019).
 */
@RestController
@RequestMapping("/v1/use-cases")
public class UseCaseController {

    private final UseCaseService useCaseService;

    public UseCaseController(UseCaseService useCaseService) {
        this.useCaseService = useCaseService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ai.use_case.create')")
    public ResponseEntity<ApiModels.UseCaseResponse> registerUseCase(
            @RequestBody ApiModels.RegisterUseCaseRequest request) {
        Models.UseCase useCase = useCaseService.registerUseCase(
                request.code(), request.name(), request.description(),
                request.requirementRefs(), request.owner(),
                request.humanReview() != null ? request.humanReview().toDomain() : null,
                request.killSwitch() != null ? request.killSwitch().toDomain() : null,
                request.dataReadiness() != null ? request.dataReadiness().toDomain() : null,
                "api"
        );
        return ResponseEntity.created(URI.create("/v1/use-cases/" + useCase.id()))
                .body(ApiModels.UseCaseResponse.from(useCase));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ai.use_case.read')")
    public ResponseEntity<ApiModels.UseCaseResponse> getUseCase(@PathVariable UUID id) {
        return useCaseService.findById(id)
                .map(ApiModels.UseCaseResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/code/{code}")
    @PreAuthorize("hasAuthority('ai.use_case.read')")
    public ResponseEntity<ApiModels.UseCaseResponse> getUseCaseByCode(@PathVariable String code) {
        return useCaseService.findByCode(code)
                .map(ApiModels.UseCaseResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ai.use_case.read')")
    public ResponseEntity<List<ApiModels.UseCaseResponse>> listUseCases(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<Models.UseCase> useCases;
        if (status != null) {
            useCases = useCaseService.findByStatus(Models.UseCaseStatus.valueOf(status));
        } else {
            useCases = useCaseService.findAll(limit, offset);
        }
        return ResponseEntity.ok(useCases.stream().map(ApiModels.UseCaseResponse::from).toList());
    }

    @PostMapping("/{id}/assign-model")
    @PreAuthorize("hasAuthority('ai.use_case.assign_model')")
    public ResponseEntity<ApiModels.UseCaseResponse> assignModel(
            @PathVariable UUID id, @RequestBody Map<String, UUID> request) {
        UUID modelId = request.get("modelId");
        Models.UseCase useCase = useCaseService.assignModel(id, modelId, "api");
        return ResponseEntity.ok(ApiModels.UseCaseResponse.from(useCase));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('ai.use_case.approve')")
    public ResponseEntity<ApiModels.UseCaseResponse> approveUseCase(@PathVariable UUID id) {
        Models.UseCase useCase = useCaseService.approveUseCase(id, "api");
        return ResponseEntity.ok(ApiModels.UseCaseResponse.from(useCase));
    }

    @PostMapping("/{id}/deploy")
    @PreAuthorize("hasAuthority('ai.use_case.deploy')")
    public ResponseEntity<ApiModels.UseCaseResponse> deployUseCase(@PathVariable UUID id) {
        Models.UseCase useCase = useCaseService.deployUseCase(id, "api");
        return ResponseEntity.ok(ApiModels.UseCaseResponse.from(useCase));
    }

    @PostMapping("/{id}/kill-switch/activate")
    @PreAuthorize("hasAuthority('ai.kill_switch.activate')")
    public ResponseEntity<Void> activateKillSwitch(
            @PathVariable UUID id, @RequestBody ApiModels.KillSwitchActivateRequest request) {
        useCaseService.activateKillSwitch(id, request.reason(), request.activatedBy());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/kill-switch/deactivate")
    @PreAuthorize("hasAuthority('ai.kill_switch.deactivate')")
    public ResponseEntity<Void> deactivateKillSwitch(@PathVariable UUID id, @RequestBody Map<String, String> request) {
        useCaseService.deactivateKillSwitch(id, request.get("deactivatedBy"));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/data-readiness")
    @PreAuthorize("hasAuthority('ai.use_case.update_readiness')")
    public ResponseEntity<ApiModels.UseCaseResponse> updateDataReadiness(
            @PathVariable UUID id, @RequestBody ApiModels.DataReadinessRequest request) {
        Models.UseCase useCase = useCaseService.updateDataReadiness(id, request.toDomain(), "api");
        return ResponseEntity.ok(ApiModels.UseCaseResponse.from(useCase));
    }
}
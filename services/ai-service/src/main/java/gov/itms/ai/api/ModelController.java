package gov.itms.ai.api;

import gov.itms.ai.application.ModelService;
import gov.itms.ai.application.Ports;
import gov.itms.ai.domain.Models;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Model registry API (AI-001, AI-004, AI-009, AI-010, AI-011).
 */
@RestController
@RequestMapping("/v1/models")
public class ModelController {

    private final ModelService modelService;

    public ModelController(ModelService modelService) {
        this.modelService = modelService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ai.model.create')")
    public ResponseEntity<ApiModels.ModelResponse> registerModel(
            @RequestBody ApiModels.RegisterModelRequest request) {
        Models.Model model = modelService.registerModel(
                request.name(), request.version(), request.description(),
                Models.ModelType.valueOf(request.type()), request.owner(),
                request.framework(), request.artifactUri(),
                request.hyperparameters(), request.accuracyTargets() != null ? request.accuracyTargets().toDomain() : null,
                "api" // createdBy from auth context
        );
        return ResponseEntity.created(URI.create("/v1/models/" + model.id()))
                .body(ApiModels.ModelResponse.from(model));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ai.model.read')")
    public ResponseEntity<ApiModels.ModelResponse> getModel(@PathVariable UUID id) {
        return modelService.findById(id)
                .map(ApiModels.ModelResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ai.model.read')")
    public ResponseEntity<List<ApiModels.ModelResponse>> listModels(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<Models.Model> models;
        if (status != null) {
            models = modelService.findByStatus(Models.ModelStatus.valueOf(status));
        } else if (type != null) {
            models = modelService.findByType(Models.ModelType.valueOf(type));
        } else {
            models = modelService.findAll(limit, offset);
        }
        return ResponseEntity.ok(models.stream().map(ApiModels.ModelResponse::from).toList());
    }

    @PostMapping("/{id}/validate")
    @PreAuthorize("hasAuthority('ai.model.validate')")
    public ResponseEntity<ApiModels.ModelResponse> submitForValidation(
            @PathVariable UUID id, @RequestBody Map<String, Object> testData) {
        // In real implementation, testData would be properly typed
        Models.Model model = modelService.submitForValidation(id, "api");
        return ResponseEntity.ok(ApiModels.ModelResponse.from(model));
    }

    @PostMapping("/{id}/evaluate")
    @PreAuthorize("hasAuthority('ai.model.validate')")
    public ResponseEntity<ApiModels.ModelResponse> evaluateModel(
            @PathVariable UUID id, @RequestBody List<Map<String, Object>> testData) {
        Models.Model model = modelService.validateModel(id, testData, "api");
        return ResponseEntity.ok(ApiModels.ModelResponse.from(model));
    }

    @PostMapping("/{id}/deploy")
    @PreAuthorize("hasAuthority('ai.model.deploy')")
    public ResponseEntity<ApiModels.ModelDeploymentResponse> deployModel(
            @PathVariable UUID id, @RequestBody ApiModels.DeployModelRequest request) {
        Models.ModelDeployment deployment = modelService.deployModel(id, request.environment(), "api");
        return ResponseEntity.ok(ApiModels.ModelDeploymentResponse.from(deployment));
    }

    @PostMapping("/{id}/deprecate")
    @PreAuthorize("hasAuthority('ai.model.deprecate')")
    public ResponseEntity<ApiModels.ModelResponse> deprecateModel(@PathVariable UUID id) {
        Models.Model model = modelService.deprecateModel(id, "api");
        return ResponseEntity.ok(ApiModels.ModelResponse.from(model));
    }
}
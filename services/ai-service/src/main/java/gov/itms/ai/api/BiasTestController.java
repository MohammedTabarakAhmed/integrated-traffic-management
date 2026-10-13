package gov.itms.ai.api;

import gov.itms.ai.application.BiasTestingService;
import gov.itms.ai.domain.Models;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bias testing API (AI-015).
 */
@RestController
@RequestMapping("/v1/bias-tests")
public class BiasTestController {

    private final BiasTestingService biasTestingService;

    public BiasTestController(BiasTestingService biasTestingService) {
        this.biasTestingService = biasTestingService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ai.bias_test.run')")
    public ResponseEntity<ApiModels.BiasTestResponse> runBiasTest(
            @RequestBody ApiModels.BiasTestRequest request) {
        Models.BiasTestResult result = biasTestingService.runBiasTest(
                request.modelId(), request.protectedAttribute(), request.referenceGroup(),
                request.comparisonGroup(), request.testData(), "api"
        );
        return ResponseEntity.ok(ApiModels.BiasTestResponse.from(result));
    }

    @GetMapping("/model/{modelId}")
    @PreAuthorize("hasAuthority('ai.bias_test.read')")
    public ResponseEntity<List<ApiModels.BiasTestResponse>> getBiasTestsForModel(@PathVariable UUID modelId) {
        List<Models.BiasTestResult> results = biasTestingService.findByModelId(modelId);
        return ResponseEntity.ok(results.stream().map(ApiModels.BiasTestResponse::from).toList());
    }

    @GetMapping("/model/{modelId}/latest")
    @PreAuthorize("hasAuthority('ai.bias_test.read')")
    public ResponseEntity<List<ApiModels.BiasTestResponse>> getLatestBiasTestsForModel(@PathVariable UUID modelId) {
        List<Models.BiasTestResult> results = biasTestingService.findLatestByModelId(modelId);
        return ResponseEntity.ok(results.stream().map(ApiModels.BiasTestResponse::from).toList());
    }

    @GetMapping("/status/{status}")
    @PreAuthorize("hasAuthority('ai.bias_test.read')")
    public ResponseEntity<List<ApiModels.BiasTestResponse>> getByStatus(@PathVariable String status) {
        List<Models.BiasTestResult> results = biasTestingService.findByStatus(Models.BiasStatus.valueOf(status));
        return ResponseEntity.ok(results.stream().map(ApiModels.BiasTestResponse::from).toList());
    }
}
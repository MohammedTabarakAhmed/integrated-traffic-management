package gov.itms.ai.api;

import gov.itms.ai.application.AdversarialTestingService;
import gov.itms.ai.domain.Models;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adversarial testing API (AI-016).
 */
@RestController
@RequestMapping("/v1/adversarial-tests")
public class AdversarialTestController {

    private final AdversarialTestingService adversarialTestingService;

    public AdversarialTestController(AdversarialTestingService adversarialTestingService) {
        this.adversarialTestingService = adversarialTestingService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ai.adversarial_test.run')")
    public ResponseEntity<ApiModels.AdversarialTestResponse> runAdversarialTest(
            @RequestBody ApiModels.AdversarialTestRequest request) {
        Models.AdversarialTestResult result = adversarialTestingService.runAdversarialTest(
                request.modelId(), request.attackType(), request.samplesTested(), request.testConfig(), "api"
        );
        return ResponseEntity.ok(ApiModels.AdversarialTestResponse.from(result));
    }

    @GetMapping("/model/{modelId}")
    @PreAuthorize("hasAuthority('ai.adversarial_test.read')")
    public ResponseEntity<List<ApiModels.AdversarialTestResponse>> getAdversarialTestsForModel(@PathVariable UUID modelId) {
        List<Models.AdversarialTestResult> results = adversarialTestingService.findByModelId(modelId);
        return ResponseEntity.ok(results.stream().map(ApiModels.AdversarialTestResponse::from).toList());
    }

    @GetMapping("/model/{modelId}/latest")
    @PreAuthorize("hasAuthority('ai.adversarial_test.read')")
    public ResponseEntity<List<ApiModels.AdversarialTestResponse>> getLatestAdversarialTestsForModel(@PathVariable UUID modelId) {
        List<Models.AdversarialTestResult> results = adversarialTestingService.findLatestByModelId(modelId);
        return ResponseEntity.ok(results.stream().map(ApiModels.AdversarialTestResponse::from).toList());
    }

    @GetMapping("/status/{status}")
    @PreAuthorize("hasAuthority('ai.adversarial_test.read')")
    public ResponseEntity<List<ApiModels.AdversarialTestResponse>> getByStatus(@PathVariable String status) {
        List<Models.AdversarialTestResult> results = adversarialTestingService.findByStatus(Models.AdversarialStatus.valueOf(status));
        return ResponseEntity.ok(results.stream().map(ApiModels.AdversarialTestResponse::from).toList());
    }
}
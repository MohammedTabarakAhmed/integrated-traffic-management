package gov.itms.ai.api;

import gov.itms.ai.application.ExplanationService;
import gov.itms.ai.domain.Models;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Model explainability API (AI-008).
 */
@RestController
@RequestMapping("/v1/explanations")
public class ExplanationController {

    private final ExplanationService explanationService;

    public ExplanationController(ExplanationService explanationService) {
        this.explanationService = explanationService;
    }

    @GetMapping("/inference/{requestId}")
    @PreAuthorize("hasAuthority('ai.explanation.read')")
    public ResponseEntity<ApiModels.ExplanationResponse> getExplanation(@PathVariable UUID requestId) {
        return explanationService.findByInferenceRequestId(requestId)
                .map(ApiModels.ExplanationResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/model/{modelId}")
    @PreAuthorize("hasAuthority('ai.explanation.read')")
    public ResponseEntity<List<ApiModels.ExplanationResponse>> getExplanationsForModel(
            @PathVariable UUID modelId,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        List<Models.Explanation> explanations = explanationService.findByModelId(modelId, from, to);
        return ResponseEntity.ok(explanations.stream().map(ApiModels.ExplanationResponse::from).toList());
    }
}
package gov.itms.ai.api;

import gov.itms.ai.application.InferenceService;
import gov.itms.ai.domain.Models;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inference API (AI-005, AI-012, AI-013).
 */
@RestController
@RequestMapping("/v1/inference")
public class InferenceController {

    private final InferenceService inferenceService;

    public InferenceController(InferenceService inferenceService) {
        this.inferenceService = inferenceService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ai.inference.execute')")
    public ResponseEntity<ApiModels.InferenceResponse> infer(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestBody ApiModels.InferenceRequest request) {
        Models.InferenceResponse response = inferenceService.infer(
                request.useCaseId(), correlationId, request.input(), request.context(), "api"
        );
        return ResponseEntity.ok(ApiModels.InferenceResponse.from(response));
    }

    @GetMapping("/history/{useCaseId}")
    @PreAuthorize("hasAuthority('ai.inference.read')")
    public ResponseEntity<List<ApiModels.InferenceResponse>> getHistory(
            @PathVariable UUID useCaseId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "100") int limit) {
        List<Models.InferenceResponse> history = inferenceService.getHistory(useCaseId, from, to, limit);
        return ResponseEntity.ok(history.stream().map(ApiModels.InferenceResponse::from).toList());
    }

    @GetMapping("/reviews/pending")
    @PreAuthorize("hasAuthority('ai.review.read')")
    public ResponseEntity<List<ApiModels.ReviewCaseResponse>> getPendingReviews(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<Models.ReviewCase> reviews = inferenceService.getPendingReviews(limit, offset);
        return ResponseEntity.ok(reviews.stream().map(ApiModels.ReviewCaseResponse::from).toList());
    }

    @GetMapping("/reviews/use-case/{useCaseId}")
    @PreAuthorize("hasAuthority('ai.review.read')")
    public ResponseEntity<List<ApiModels.ReviewCaseResponse>> getReviewsForUseCase(
            @PathVariable UUID useCaseId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "100") int limit) {
        List<Models.ReviewCase> reviews = inferenceService.getReviewsForUseCase(useCaseId, from, to, limit);
        return ResponseEntity.ok(reviews.stream().map(ApiModels.ReviewCaseResponse::from).toList());
    }
}
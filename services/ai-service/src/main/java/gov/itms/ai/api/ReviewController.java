package gov.itms.ai.api;

import gov.itms.ai.application.ReviewService;
import gov.itms.ai.domain.Models;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Human-in-the-loop review API (AI-012, AI-013).
 */
@RestController
@RequestMapping("/v1/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ai.review.read')")
    public ResponseEntity<ApiModels.ReviewCaseResponse> getReview(@PathVariable UUID id) {
        return reviewService.findById(id)
                .map(ApiModels.ReviewCaseResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ai.review.read')")
    public ResponseEntity<List<ApiModels.ReviewCaseResponse>> listReviews(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assignee,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<Models.ReviewCase> reviews;
        if (assignee != null) {
            reviews = reviewService.findByAssignee(assignee, limit, offset);
        } else if (status != null) {
            reviews = reviewService.findByStatus(status, limit, offset);
        } else {
            reviews = reviewService.findByStatus("PENDING", limit, offset);
        }
        return ResponseEntity.ok(reviews.stream().map(ApiModels.ReviewCaseResponse::from).toList());
    }

    @GetMapping("/use-case/{useCaseId}")
    @PreAuthorize("hasAuthority('ai.review.read')")
    public ResponseEntity<List<ApiModels.ReviewCaseResponse>> getReviewsForUseCase(
            @PathVariable UUID useCaseId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "100") int limit) {
        List<Models.ReviewCase> reviews = reviewService.findByUseCaseId(useCaseId, from, to, limit);
        return ResponseEntity.ok(reviews.stream().map(ApiModels.ReviewCaseResponse::from).toList());
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('ai.review.assign')")
    public ResponseEntity<ApiModels.ReviewCaseResponse> assignReview(
            @PathVariable UUID id, @RequestBody Map<String, String> request) {
        Models.ReviewCase reviewCase = reviewService.assignReview(
                id, request.get("reviewerId"), "api"
        );
        return ResponseEntity.ok(ApiModels.ReviewCaseResponse.from(reviewCase));
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasAuthority('ai.review.decide')")
    public ResponseEntity<ApiModels.ReviewCaseResponse> submitDecision(
            @PathVariable UUID id, @RequestBody ApiModels.ReviewDecisionRequest request) {
        Models.ReviewCase reviewCase = reviewService.submitDecision(
                id, request.reviewerId(), request.decision(), request.notes()
        );
        return ResponseEntity.ok(ApiModels.ReviewCaseResponse.from(reviewCase));
    }

    @PostMapping("/{id}/escalate")
    @PreAuthorize("hasAuthority('ai.review.escalate')")
    public ResponseEntity<ApiModels.ReviewCaseResponse> escalateReview(
            @PathVariable UUID id, @RequestBody Map<String, String> request) {
        Models.ReviewCase reviewCase = reviewService.escalateReview(id, request.get("escalatedBy"));
        return ResponseEntity.ok(ApiModels.ReviewCaseResponse.from(reviewCase));
    }
}
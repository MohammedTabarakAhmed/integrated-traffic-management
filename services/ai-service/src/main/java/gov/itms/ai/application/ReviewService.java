package gov.itms.ai.application;

import gov.itms.ai.domain.Models;
import gov.itms.ai.domain.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Human-in-the-loop review service (AI-012, AI-013).
 */
public final class ReviewService {

    private final Ports.ReviewRepository reviewRepository;
    private final Ports.UseCaseRepository useCaseRepository;
    private final Ports.InferenceLogRepository inferenceLogRepository;

    public ReviewService(Ports.ReviewRepository reviewRepository,
                         Ports.UseCaseRepository useCaseRepository,
                         Ports.InferenceLogRepository inferenceLogRepository) {
        this.reviewRepository = reviewRepository;
        this.useCaseRepository = useCaseRepository;
        this.inferenceLogRepository = inferenceLogRepository;
    }

    /** Assign review case to reviewer. */
    public Models.ReviewCase assignReview(UUID reviewCaseId, String reviewerId, String assignedBy) {
        Models.ReviewCase reviewCase = reviewRepository.findById(reviewCaseId)
                .orElseThrow(() -> DomainException.notFound("REVIEW_CASE_NOT_FOUND",
                        "Review case %s not found".formatted(reviewCaseId), List.of("AI-012"), reviewCaseId));

        if (!"PENDING".equals(reviewCase.status())) {
            throw DomainException.validation("INVALID_REVIEW_STATE",
                    "Review case is not pending: %s".formatted(reviewCase.status()),
                    List.of("AI-012"), reviewCase.status());
        }

        Models.ReviewCase updated = new Models.ReviewCase(
                reviewCase.id(), reviewCase.inferenceRequestId(), reviewCase.useCaseId(),
                "ASSIGNED", reviewerId, reviewCase.inferenceOutput(),
                reviewCase.reviewerDecision(), reviewCase.reviewerNotes(),
                reviewCase.createdAt(), reviewCase.reviewedAt(), reviewCase.reviewedBy()
        );

        reviewRepository.updateCase(updated);
        return updated;
    }

    /** Submit review decision (AI-013). */
    public Models.ReviewCase submitDecision(UUID reviewCaseId, String reviewerId,
                                             Map<String, Object> decision, String notes) {
        Models.ReviewCase reviewCase = reviewRepository.findById(reviewCaseId)
                .orElseThrow(() -> DomainException.notFound("REVIEW_CASE_NOT_FOUND",
                        "Review case %s not found".formatted(reviewCaseId), List.of("AI-013"), reviewCaseId));

        if (!reviewerId.equals(reviewCase.assignedReviewer())) {
            throw DomainException.validation("NOT_ASSIGNED_REVIEWER",
                    "Reviewer %s is not assigned to this case".formatted(reviewerId),
                    List.of("AI-013"), reviewerId, reviewCase.assignedReviewer());
        }

        if (!"ASSIGNED".equals(reviewCase.status()) && !"PENDING".equals(reviewCase.status())) {
            throw DomainException.validation("INVALID_REVIEW_STATE",
                    "Review case cannot accept decision in state: %s".formatted(reviewCase.status()),
                    List.of("AI-013"), reviewCase.status());
        }

        // Check if consensus required
        Models.UseCase useCase = useCaseRepository.findById(reviewCase.useCaseId())
                .orElseThrow(() -> DomainException.notFound("USE_CASE_NOT_FOUND",
                        "Use case %s not found".formatted(reviewCase.useCaseId()), List.of("AI-013"), reviewCase.useCaseId()));

        String newStatus = "COMPLETED";
        if (useCase.requiresHumanReview() && useCase.humanReview().requireConsensus()) {
            // In a real implementation, check other reviewers' decisions
            // For now, assume single reviewer or consensus achieved
            newStatus = "COMPLETED";
        }

        Models.ReviewCase updated = new Models.ReviewCase(
                reviewCase.id(), reviewCase.inferenceRequestId(), reviewCase.useCaseId(),
                newStatus, reviewerId, reviewCase.inferenceOutput(),
                decision, notes, reviewCase.createdAt(), Instant.now(), reviewerId
        );

        reviewRepository.updateCase(updated);
        return updated;
    }

    /** Escalate review case (AI-013). */
    public Models.ReviewCase escalateReview(UUID reviewCaseId, String escalatedBy) {
        Models.ReviewCase reviewCase = reviewRepository.findById(reviewCaseId)
                .orElseThrow(() -> DomainException.notFound("REVIEW_CASE_NOT_FOUND",
                        "Review case %s not found".formatted(reviewCaseId), List.of("AI-013"), reviewCaseId));

        Models.UseCase useCase = useCaseRepository.findById(reviewCase.useCaseId())
                .orElseThrow(() -> DomainException.notFound("USE_CASE_NOT_FOUND",
                        "Use case %s not found".formatted(reviewCase.useCaseId()), List.of("AI-013"), reviewCase.useCaseId()));

        String escalationRole = useCase.humanReview() != null && useCase.humanReview().escalation() != null
                ? useCase.humanReview().escalation().escalationRole()
                : "AI_ADMIN";

        Models.ReviewCase updated = new Models.ReviewCase(
                reviewCase.id(), reviewCase.inferenceRequestId(), reviewCase.useCaseId(),
                "ESCALATED", escalationRole, reviewCase.inferenceOutput(),
                reviewCase.reviewerDecision(), reviewCase.reviewerNotes(),
                reviewCase.createdAt(), reviewCase.reviewedAt(), reviewCase.reviewedBy()
        );

        reviewRepository.updateCase(updated);
        return updated;
    }

    public Optional<Models.ReviewCase> findById(UUID id) {
        return reviewRepository.findById(id);
    }

    public List<Models.ReviewCase> findByStatus(String status, int limit, int offset) {
        return reviewRepository.findByStatus(status, limit, offset);
    }

    public List<Models.ReviewCase> findByAssignee(String assignee, int limit, int offset) {
        return reviewRepository.findByAssignee(assignee, limit, offset);
    }

    public List<Models.ReviewCase> findByUseCaseId(UUID useCaseId, Instant from, Instant to, int limit) {
        return reviewRepository.findByUseCaseId(useCaseId, from, to, limit);
    }
}
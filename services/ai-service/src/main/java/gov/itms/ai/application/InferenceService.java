package gov.itms.ai.application;

import gov.itms.ai.domain.Models;
import gov.itms.ai.domain.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Inference pipeline with human-in-the-loop and kill switch (AI-005, AI-012, AI-013, AI-014).
 */
public final class InferenceService {

    private final Ports.UseCaseRepository useCaseRepository;
    private final Ports.ModelRepository modelRepository;
    private final Ports.ModelInference modelInference;
    private final Ports.InferenceLogRepository inferenceLogRepository;
    private final Ports.ReviewRepository reviewRepository;
    private final Ports.KillSwitch killSwitch;
    private final Ports.ExplanationRepository explanationRepository;
    private final ExplanationService explanationService;

    public InferenceService(Ports.UseCaseRepository useCaseRepository,
                            Ports.ModelRepository modelRepository,
                            Ports.ModelInference modelInference,
                            Ports.InferenceLogRepository inferenceLogRepository,
                            Ports.ReviewRepository reviewRepository,
                            Ports.KillSwitch killSwitch,
                            Ports.ExplanationRepository explanationRepository,
                            ExplanationService explanationService) {
        this.useCaseRepository = useCaseRepository;
        this.modelRepository = modelRepository;
        this.modelInference = modelInference;
        this.inferenceLogRepository = inferenceLogRepository;
        this.reviewRepository = reviewRepository;
        this.killSwitch = killSwitch;
        this.explanationRepository = explanationRepository;
        this.explanationService = explanationService;
    }

    /** Run inference for a use case (AI-005). */
    public Models.InferenceResponse infer(UUID useCaseId, String correlationId,
                                           Map<String, Object> input, Map<String, String> context,
                                           String requestedBy) {
        Models.UseCase useCase = useCaseRepository.findById(useCaseId)
                .orElseThrow(() -> DomainException.notFound("USE_CASE_NOT_FOUND",
                        "Use case %s not found".formatted(useCaseId), List.of("AI-005"), useCaseId));

        if (useCase.status() != Models.UseCaseStatus.DEPLOYED) {
            throw DomainException.validation("USE_CASE_NOT_DEPLOYED",
                    "Use case must be DEPLOYED for inference",
                    List.of("AI-005"), useCase.status());
        }

        // Check kill switch
        if (killSwitch.isActive(Models.KillSwitchScope.USE_CASE) ||
            (useCase.killSwitch() != null && useCase.killSwitch().enabled() &&
             killSwitch.isActive(Models.KillSwitchScope.USE_CASE))) {
            return createKillSwitchResponse(useCaseId, correlationId, requestedBy);
        }

        if (killSwitch.isActive(Models.KillSwitchScope.SERVICE_WIDE)) {
            return createKillSwitchResponse(useCaseId, correlationId, requestedBy);
        }

        Models.Model model = useCase.model();
        if (model == null) {
            throw DomainException.validation("NO_MODEL_ASSIGNED",
                    "Use case has no assigned model",
                    List.of("AI-005"));
        }

        // Check model health
        if (!modelInference.isHealthy(model)) {
            throw DomainException.modelUnavailable("MODEL_UNHEALTHY",
                    "Model %s is unhealthy".formatted(model.id()), List.of("AI-005"), model.id());
        }

        // Log request
        Models.InferenceRequest request = new Models.InferenceRequest(
                UUID.randomUUID(), useCaseId, correlationId, input, context, Instant.now(), requestedBy
        );
        inferenceLogRepository.insertRequest(request);

        // Run inference
        long startMs = System.currentTimeMillis();
        var prediction = modelInference.predict(model, input);
        long latencyMs = System.currentTimeMillis() - startMs;

        // Determine status based on confidence and human review config
        Models.InferenceStatus status = determineStatus(prediction.confidence(), useCase);

        // Generate explanation
        Models.Explanation explanation = explanationService.generateExplanation(request.requestId(), model, input, prediction);
        explanationRepository.insertExplanation(explanation);

        // Create response
        Models.InferenceResponse response = new Models.InferenceResponse(
                request.requestId(), useCaseId, model.id(), model.version(),
                prediction.output(), prediction.confidence(),
                prediction.metadata(), status, Instant.now(), latencyMs
        );

        inferenceLogRepository.insertResponse(response);

        // Create review case if needed
        if (status == Models.InferenceStatus.HUMAN_REVIEW_REQUIRED && useCase.requiresHumanReview()) {
            createReviewCase(request, response, useCase);
        }

        return response;
    }

    /** Async inference for high throughput. */
    public CompletableFuture<Models.InferenceResponse> inferAsync(UUID useCaseId, String correlationId,
                                                                   Map<String, Object> input, Map<String, String> context,
                                                                   String requestedBy) {
        return CompletableFuture.supplyAsync(() -> infer(useCaseId, correlationId, input, context, requestedBy));
    }

    private Models.InferenceStatus determineStatus(double confidence, Models.UseCase useCase) {
        if (confidence < 0.5) {
            return Models.InferenceStatus.LOW_CONFIDENCE;
        }

        if (useCase.requiresHumanReview()) {
            var trigger = useCase.humanReview().trigger();
            if (trigger == Models.ReviewTrigger.ALWAYS ||
                (trigger == Models.ReviewTrigger.LOW_CONFIDENCE && confidence < 0.8) ||
                (trigger == Models.ReviewTrigger.HIGH_RISK && confidence < 0.9)) {
                return Models.InferenceStatus.HUMAN_REVIEW_REQUIRED;
            }
        }

        return Models.InferenceStatus.SUCCESS;
    }

    private Models.InferenceResponse createKillSwitchResponse(UUID useCaseId, String correlationId, String requestedBy) {
        Models.InferenceResponse response = new Models.InferenceResponse(
                UUID.randomUUID(), useCaseId, null, null,
                Map.of("error", "KILL_SWITCH_ACTIVE", "message", "AI automation temporarily disabled"),
                0.0, Map.of(), Models.InferenceStatus.KILL_SWITCH_ACTIVE, Instant.now(), 0L
        );
        inferenceLogRepository.insertResponse(response);
        return response;
    }

    private void createReviewCase(Models.InferenceRequest request, Models.InferenceResponse response, Models.UseCase useCase) {
        Models.ReviewCase reviewCase = new Models.ReviewCase(
                UUID.randomUUID(), request.requestId(), useCase.id(),
                "PENDING", null, response.output(), null, null,
                Instant.now(), null, null
        );
        reviewRepository.insertCase(reviewCase);
    }

    /** Get inference history for use case. */
    public List<Models.InferenceResponse> getHistory(UUID useCaseId, Instant from, Instant to, int limit) {
        return inferenceLogRepository.findByUseCaseId(useCaseId, from, to, limit);
    }

    /** Get pending review cases. */
    public List<Models.ReviewCase> getPendingReviews(int limit, int offset) {
        return reviewRepository.findByStatus("PENDING", limit, offset);
    }

    /** Get review cases for use case. */
    public List<Models.ReviewCase> getReviewsForUseCase(UUID useCaseId, Instant from, Instant to, int limit) {
        return reviewRepository.findByUseCaseId(useCaseId, from, to, limit);
    }
}
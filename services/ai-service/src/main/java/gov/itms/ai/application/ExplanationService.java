package gov.itms.ai.application;

import java.util.List;

import gov.itms.ai.domain.Models;
import gov.itms.ai.domain.DomainException;
import gov.itms.ai.application.Ports.ModelInference;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Model explainability service (AI-008).
 */
public final class ExplanationService {

    private final Ports.ExplanationRepository explanationRepository;
    private final Ports.ModelInference modelInference;

    public ExplanationService(Ports.ExplanationRepository explanationRepository,
                               Ports.ModelInference modelInference) {
        this.explanationRepository = explanationRepository;
        this.modelInference = modelInference;
    }

    /** Generate explanation for an inference (AI-008). */
    public Models.Explanation generateExplanation(UUID inferenceRequestId, Models.Model model,
                                                   Map<String, Object> input,
                                                   ModelInference.Prediction prediction) {
        // Choose explanation method based on model type
        Models.ExplanationMethod method = selectMethod(model.type());

        // Generate feature importance (simplified - in reality would use SHAP, LIME, etc.)
        Map<String, Double> featureImportance = computeFeatureImportance(input, prediction, method);

        // Generate local explanation
        Map<String, Object> localExplanation = computeLocalExplanation(input, prediction, method);

        // Generate global summary
        String globalSummary = generateGlobalSummary(model, method);

        Models.Explanation explanation = new Models.Explanation(
                inferenceRequestId, method, featureImportance, localExplanation,
                globalSummary, Instant.now()
        );

        explanationRepository.insertExplanation(explanation);
        return explanation;
    }

    private Models.ExplanationMethod selectMethod(Models.ModelType type) {
        return switch (type) {
            case CLASSIFICATION, REGRESSION -> Models.ExplanationMethod.SHAP;
            case ANOMALY_DETECTION -> Models.ExplanationMethod.PERMUTATION;
            case NLP -> Models.ExplanationMethod.INTEGRATED_GRADIENTS;
            case COMPUTER_VISION -> Models.ExplanationMethod.INTEGRATED_GRADIENTS;
            case ENSEMBLE -> Models.ExplanationMethod.SHAP;
            case RULE_BASED -> Models.ExplanationMethod.RULE_BASED;
        };
    }

    private Map<String, Double> computeFeatureImportance(Map<String, Object> input,
                                                          ModelInference.Prediction prediction,
                                                          Models.ExplanationMethod method) {
        // Simplified: return equal importance for all features
        // Real implementation would use SHAP, LIME, etc.
        return input.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> 1.0 / input.size()
                ));
    }

    private Map<String, Object> computeLocalExplanation(Map<String, Object> input,
                                                         ModelInference.Prediction prediction,
                                                         Models.ExplanationMethod method) {
        return Map.of(
                "method", method.name(),
                "prediction", prediction.output(),
                "confidence", prediction.confidence(),
                "inputFeatures", input
        );
    }

    private String generateGlobalSummary(Models.Model model, Models.ExplanationMethod method) {
        return "%s model (%s) explained using %s. Feature contributions are computed per inference."
                .formatted(model.name(), model.type(), method);
    }

    public Optional<Models.Explanation> findByInferenceRequestId(UUID requestId) {
        return explanationRepository.findByInferenceRequestId(requestId);
    }

    public List<Models.Explanation> findByModelId(UUID modelId, Instant from, Instant to) {
        return explanationRepository.findByModelId(modelId, from, to);
    }
}
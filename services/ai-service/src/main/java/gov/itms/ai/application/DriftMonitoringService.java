package gov.itms.ai.application;

import gov.itms.ai.domain.Models;
import gov.itms.ai.domain.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Drift monitoring service (AI-006, AI-007).
 */
public final class DriftMonitoringService {

    private final Ports.DriftRepository driftRepository;
    private final Ports.ModelRepository modelRepository;
    private final Ports.InferenceLogRepository inferenceLogRepository;
    private final Ports.KillSwitch killSwitch;

    public DriftMonitoringService(Ports.DriftRepository driftRepository,
                                   Ports.ModelRepository modelRepository,
                                   Ports.InferenceLogRepository inferenceLogRepository,
                                   Ports.KillSwitch killSwitch) {
        this.driftRepository = driftRepository;
        this.modelRepository = modelRepository;
        this.inferenceLogRepository = inferenceLogRepository;
        this.killSwitch = killSwitch;
    }

    /** Compute and record drift metrics for a model (AI-006, AI-007). */
    public Models.DriftMetrics computeDrift(UUID modelId, Instant windowStart, Instant windowEnd,
                                             Map<String, Double> featureDriftDetails,
                                             String computedBy) {
        Models.Model model = modelRepository.findById(modelId)
                .orElseThrow(() -> DomainException.notFound("MODEL_NOT_FOUND",
                        "Model %s not found".formatted(modelId), List.of("AI-006"), modelId));

        // In a real implementation, this would compute PSI, feature drift, etc.
        // using reference data vs current inference data
        double psi = computePSI(featureDriftDetails);
        double featureDrift = featureDriftDetails.values().stream().mapToDouble(d -> d).average().orElse(0.0);
        double predictionDrift = computePredictionDrift(modelId, windowStart, windowEnd);
        double conceptDrift = computeConceptDrift(modelId, windowStart, windowEnd);

        Models.DriftStatus status = determineDriftStatus(psi, featureDrift, predictionDrift, conceptDrift);

        // Activate kill switch if drift is critical
        if (status == Models.DriftStatus.CRITICAL || status == Models.DriftStatus.BLOCKED) {
            killSwitch.activate("DRIFT_DETECTED: %s".formatted(status), "DRIFT_MONITOR", Models.KillSwitchScope.MODEL);
        }

        Models.DriftMetrics metrics = new Models.DriftMetrics(
                modelId, windowStart, windowEnd, psi, featureDrift, predictionDrift, conceptDrift,
                featureDriftDetails, status
        );

        driftRepository.insertMetrics(metrics);
        return metrics;
    }

    /** Scheduled drift check for all active models. */
    public List<Models.DriftMetrics> checkAllModels(Instant windowStart, Instant windowEnd) {
        List<Models.Model> activeModels = modelRepository.findByStatus(Models.ModelStatus.ACTIVE);
        return activeModels.stream()
                .map(model -> computeDrift(model.id(), windowStart, windowEnd, Map.of(), "SCHEDULED"))
                .toList();
    }

    private double computePSI(Map<String, Double> featureDriftDetails) {
        // Population Stability Index calculation
        return featureDriftDetails.values().stream().mapToDouble(d -> d * d).sum() / Math.max(1, featureDriftDetails.size());
    }

    private double computePredictionDrift(UUID modelId, Instant from, Instant to) {
        // Compare prediction distribution vs reference
        var responses = inferenceLogRepository.findByModelId(modelId, from, to, 10000);
        if (responses.isEmpty()) return 0.0;
        // Simplified: variance in confidence scores
        double avgConfidence = responses.stream().mapToDouble(Models.InferenceResponse::confidence).average().orElse(0.0);
        return Math.abs(avgConfidence - 0.8); // Reference confidence
    }

    private double computeConceptDrift(UUID modelId, Instant from, Instant to) {
        // Concept drift: relationship between features and target changes
        // Simplified implementation
        return 0.0;
    }

    private Models.DriftStatus determineDriftStatus(double psi, double featureDrift, double predictionDrift, double conceptDrift) {
        double maxDrift = Math.max(Math.max(psi, featureDrift), Math.max(predictionDrift, conceptDrift));
        if (maxDrift > 0.5) return Models.DriftStatus.BLOCKED;
        if (maxDrift > 0.2) return Models.DriftStatus.CRITICAL;
        if (maxDrift > 0.1) return Models.DriftStatus.WARNING;
        return Models.DriftStatus.STABLE;
    }

    public Optional<Models.DriftMetrics> findLatest(UUID modelId) {
        return driftRepository.findLatest(modelId);
    }

    public List<Models.DriftMetrics> findByModelId(UUID modelId, Instant from, Instant to) {
        return driftRepository.findByModelId(modelId, from, to);
    }

    public List<Models.DriftMetrics> findByStatus(Models.DriftStatus status) {
        return driftRepository.findByStatus(status);
    }
}
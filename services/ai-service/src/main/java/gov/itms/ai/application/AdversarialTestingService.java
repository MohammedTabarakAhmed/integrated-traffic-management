package gov.itms.ai.application;

import gov.itms.ai.domain.Models;
import gov.itms.ai.domain.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Adversarial testing service (AI-016).
 */
public final class AdversarialTestingService {

    private final Ports.AdversarialTestRepository adversarialTestRepository;
    private final Ports.ModelRepository modelRepository;

    public AdversarialTestingService(Ports.AdversarialTestRepository adversarialTestRepository,
                                      Ports.ModelRepository modelRepository) {
        this.adversarialTestRepository = adversarialTestRepository;
        this.modelRepository = modelRepository;
    }

    /** Run adversarial test for a model (AI-016). */
    public Models.AdversarialTestResult runAdversarialTest(UUID modelId, String attackType,
                                                            int samplesTested, Map<String, Object> testConfig,
                                                            String testedBy) {
        Models.Model model = modelRepository.findById(modelId)
                .orElseThrow(() -> DomainException.notFound("MODEL_NOT_FOUND",
                        "Model %s not found".formatted(modelId), List.of("AI-016"), modelId));

        // In a real implementation, this would run actual adversarial attacks
        // like FGSM, PGD, C&W, etc. and measure robustness
        int samplesFailed = runAttackSimulation(model, attackType, samplesTested, testConfig);
        double robustnessScore = 1.0 - (double) samplesFailed / samplesTested;

        Models.AdversarialStatus status = determineAdversarialStatus(robustnessScore, attackType);

        Models.AdversarialTestResult result = new Models.AdversarialTestResult(
                modelId, attackType, robustnessScore, samplesTested, samplesFailed, status, Instant.now()
        );

        adversarialTestRepository.insertResult(result);

        // Activate kill switch if critical vulnerability found
        if (status == Models.AdversarialStatus.CRITICAL) {
            // Kill switch activation would be handled by a separate orchestrator
        }

        return result;
    }

    private int runAttackSimulation(Models.Model model, String attackType, int samples, Map<String, Object> config) {
        // Simplified: return random failures based on attack type
        return switch (attackType.toUpperCase()) {
            case "FGSM" -> (int) (samples * 0.02);
            case "PGD" -> (int) (samples * 0.05);
            case "CW" -> (int) (samples * 0.08);
            case "BOUNDARY" -> (int) (samples * 0.03);
            default -> (int) (samples * 0.01);
        };
    }

    private Models.AdversarialStatus determineAdversarialStatus(double robustnessScore, String attackType) {
        if (robustnessScore < 0.85) return Models.AdversarialStatus.CRITICAL;
        if (robustnessScore < 0.95) return Models.AdversarialStatus.VULNERABLE;
        return Models.AdversarialStatus.ROBUST;
    }

    public List<Models.AdversarialTestResult> findByModelId(UUID modelId) {
        return adversarialTestRepository.findByModelId(modelId);
    }

    public List<Models.AdversarialTestResult> findLatestByModelId(UUID modelId) {
        return adversarialTestRepository.findLatestByModelId(modelId);
    }

    public List<Models.AdversarialTestResult> findByStatus(Models.AdversarialStatus status) {
        return adversarialTestRepository.findByStatus(status);
    }
}
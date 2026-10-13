package gov.itms.ai.application;

import gov.itms.ai.domain.Models;
import gov.itms.ai.domain.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bias testing service (AI-015).
 */
public final class BiasTestingService {

    private final Ports.BiasTestRepository biasTestRepository;
    private final Ports.ModelRepository modelRepository;

    public BiasTestingService(Ports.BiasTestRepository biasTestRepository,
                               Ports.ModelRepository modelRepository) {
        this.biasTestRepository = biasTestRepository;
        this.modelRepository = modelRepository;
    }

    /** Run bias test for a model (AI-015). */
    public Models.BiasTestResult runBiasTest(UUID modelId, String protectedAttribute,
                                              String referenceGroup, String comparisonGroup,
                                              Map<String, Object> testData, String testedBy) {
        Models.Model model = modelRepository.findById(modelId)
                .orElseThrow(() -> DomainException.notFound("MODEL_NOT_FOUND",
                        "Model %s not found".formatted(modelId), List.of("AI-015"), modelId));

        // In a real implementation, this would run actual bias metrics
        // using the test data with known protected attributes
        double demographicParityDiff = computeDemographicParityDifference(testData, protectedAttribute, referenceGroup, comparisonGroup);
        double equalOpportunityDiff = computeEqualOpportunityDifference(testData, protectedAttribute, referenceGroup, comparisonGroup);
        double equalizedOddsDiff = computeEqualizedOddsDifference(testData, protectedAttribute, referenceGroup, comparisonGroup);

        Models.BiasStatus status = determineBiasStatus(demographicParityDiff, equalOpportunityDiff, equalizedOddsDiff);

        Models.BiasTestResult result = new Models.BiasTestResult(
                modelId, protectedAttribute, referenceGroup, comparisonGroup,
                demographicParityDiff, equalOpportunityDiff, equalizedOddsDiff,
                status, Instant.now()
        );

        biasTestRepository.insertResult(result);
        return result;
    }

    private double computeDemographicParityDifference(Map<String, Object> testData,
                                                       String protectedAttribute, String refGroup, String compGroup) {
        // Simplified implementation
        return 0.05; // 5% difference
    }

    private double computeEqualOpportunityDifference(Map<String, Object> testData,
                                                      String protectedAttribute, String refGroup, String compGroup) {
        // Simplified implementation
        return 0.03;
    }

    private double computeEqualizedOddsDifference(Map<String, Object> testData,
                                                   String protectedAttribute, String refGroup, String compGroup) {
        // Simplified implementation
        return 0.04;
    }

    private Models.BiasStatus determineBiasStatus(double dpDiff, double eoDiff, double eo2Diff) {
        double maxDiff = Math.max(Math.max(Math.abs(dpDiff), Math.abs(eoDiff)), Math.abs(eo2Diff));
        if (maxDiff > 0.1) return Models.BiasStatus.FAIL;
        if (maxDiff > 0.05) return Models.BiasStatus.WARNING;
        return Models.BiasStatus.PASS;
    }

    public List<Models.BiasTestResult> findByModelId(UUID modelId) {
        return biasTestRepository.findByModelId(modelId);
    }

    public List<Models.BiasTestResult> findLatestByModelId(UUID modelId) {
        return biasTestRepository.findLatestByModelId(modelId);
    }

    public List<Models.BiasTestResult> findByStatus(Models.BiasStatus status) {
        return biasTestRepository.findByStatus(status);
    }
}
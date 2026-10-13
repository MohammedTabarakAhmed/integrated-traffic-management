package gov.itms.ai.infrastructure;

import gov.itms.ai.application.Ports;
import gov.itms.ai.application.*;
import gov.itms.ai.infrastructure.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AI service infrastructure configuration.
 */
@Configuration
public class AiServiceConfiguration {

    @Bean
    public ModelService modelService(Ports.ModelRepository modelRepository,
                                      Ports.ModelEvaluator evaluator) {
        return new ModelService(modelRepository, evaluator);
    }

    @Bean
    public UseCaseService useCaseService(Ports.UseCaseRepository useCaseRepository,
                                          Ports.ModelRepository modelRepository,
                                          Ports.KillSwitch killSwitch) {
        return new UseCaseService(useCaseRepository, modelRepository, killSwitch);
    }

    @Bean
    public InferenceService inferenceService(Ports.UseCaseRepository useCaseRepository,
                                              Ports.ModelRepository modelRepository,
                                              Ports.ModelInference modelInference,
                                              Ports.InferenceLogRepository inferenceLogRepository,
                                              Ports.ReviewRepository reviewRepository,
                                              Ports.KillSwitch killSwitch,
                                              Ports.ExplanationRepository explanationRepository,
                                              ExplanationService explanationService) {
        return new InferenceService(useCaseRepository, modelRepository, modelInference,
                inferenceLogRepository, reviewRepository, killSwitch,
                explanationRepository, explanationService);
    }

    @Bean
    public ReviewService reviewService(Ports.ReviewRepository reviewRepository,
                                        Ports.UseCaseRepository useCaseRepository,
                                        Ports.InferenceLogRepository inferenceLogRepository) {
        return new ReviewService(reviewRepository, useCaseRepository, inferenceLogRepository);
    }

    @Bean
    public DriftMonitoringService driftMonitoringService(Ports.DriftRepository driftRepository,
                                                          Ports.ModelRepository modelRepository,
                                                          Ports.InferenceLogRepository inferenceLogRepository,
                                                          Ports.KillSwitch killSwitch) {
        return new DriftMonitoringService(driftRepository, modelRepository, inferenceLogRepository, killSwitch);
    }

    @Bean
    public ExplanationService explanationService(Ports.ExplanationRepository explanationRepository,
                                                  Ports.ModelInference modelInference) {
        return new ExplanationService(explanationRepository, modelInference);
    }

    @Bean
    public BiasTestingService biasTestingService(Ports.BiasTestRepository biasTestRepository,
                                                  Ports.ModelRepository modelRepository) {
        return new BiasTestingService(biasTestRepository, modelRepository);
    }

    @Bean
    public AdversarialTestingService adversarialTestingService(Ports.AdversarialTestRepository adversarialTestRepository,
                                                                Ports.ModelRepository modelRepository) {
        return new AdversarialTestingService(adversarialTestRepository, modelRepository);
    }

    @Bean
    public KillSwitchService killSwitchService(Ports.KillSwitch killSwitch,
                                                Ports.UseCaseRepository useCaseRepository,
                                                Ports.ModelRepository modelRepository) {
        return new KillSwitchService(killSwitch, useCaseRepository, modelRepository);
    }

    // Infrastructure beans
    @Bean
    public Ports.ModelRepository modelRepository(JdbcTemplate jdbc) {
        return new JdbcModelRepository(jdbc);
    }

    @Bean
    public Ports.UseCaseRepository useCaseRepository(JdbcTemplate jdbc) {
        return new JdbcUseCaseRepository(jdbc);
    }

    @Bean
    public Ports.InferenceLogRepository inferenceLogRepository(JdbcTemplate jdbc) {
        return new JdbcInferenceLogRepository(jdbc);
    }

    @Bean
    public Ports.ReviewRepository reviewRepository(JdbcTemplate jdbc) {
        return new JdbcReviewRepository(jdbc);
    }

    @Bean
    public Ports.DriftRepository driftRepository(JdbcTemplate jdbc) {
        return new JdbcDriftRepository(jdbc);
    }

    @Bean
    public Ports.BiasTestRepository biasTestRepository(JdbcTemplate jdbc) {
        return new JdbcBiasTestRepository(jdbc);
    }

    @Bean
    public Ports.AdversarialTestRepository adversarialTestRepository(JdbcTemplate jdbc) {
        return new JdbcAdversarialTestRepository(jdbc);
    }

    @Bean
    public Ports.ExplanationRepository explanationRepository(JdbcTemplate jdbc) {
        return new JdbcExplanationRepository(jdbc);
    }

    @Bean
    public Ports.ModelInference modelInference() {
        return new SpringAiModelInference();
    }

    @Bean
    public Ports.KillSwitch killSwitch() {
        return new InMemoryKillSwitch();
    }

    @Bean
    public Ports.FeatureStore featureStore(JdbcTemplate jdbc) {
        return new JdbcFeatureStore(jdbc);
    }

    @Bean
    public Ports.ModelEvaluator modelEvaluator() {
        return new SimpleModelEvaluator();
    }
}
package gov.itms.ai.api;

import gov.itms.ai.application.DriftMonitoringService;
import gov.itms.ai.domain.Models;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drift monitoring API (AI-006, AI-007).
 */
@RestController
@RequestMapping("/v1/drift")
public class DriftController {

    private final DriftMonitoringService driftMonitoringService;

    public DriftController(DriftMonitoringService driftMonitoringService) {
        this.driftMonitoringService = driftMonitoringService;
    }

    @PostMapping("/{modelId}/check")
    @PreAuthorize("hasAuthority('ai.drift.check')")
    public ResponseEntity<ApiModels.DriftMetricsResponse> checkDrift(
            @PathVariable UUID modelId,
            @RequestBody Map<String, Object> request) {
        Instant from = Instant.parse((String) request.get("from"));
        Instant to = Instant.parse((String) request.get("to"));
        @SuppressWarnings("unchecked")
        Map<String, Double> featureDriftDetails = (Map<String, Double>) request.getOrDefault("featureDriftDetails", Map.of());

        Models.DriftMetrics metrics = driftMonitoringService.computeDrift(modelId, from, to, featureDriftDetails, "api");
        return ResponseEntity.ok(ApiModels.DriftMetricsResponse.from(metrics));
    }

    @PostMapping("/check-all")
    @PreAuthorize("hasAuthority('ai.drift.check_all')")
    public ResponseEntity<List<ApiModels.DriftMetricsResponse>> checkAllModels(
            @RequestBody Map<String, String> request) {
        Instant from = Instant.parse(request.get("from"));
        Instant to = Instant.parse(request.get("to"));

        List<Models.DriftMetrics> results = driftMonitoringService.checkAllModels(from, to);
        return ResponseEntity.ok(results.stream().map(ApiModels.DriftMetricsResponse::from).toList());
    }

    @GetMapping("/{modelId}/latest")
    @PreAuthorize("hasAuthority('ai.drift.read')")
    public ResponseEntity<ApiModels.DriftMetricsResponse> getLatestDrift(@PathVariable UUID modelId) {
        return driftMonitoringService.findLatest(modelId)
                .map(ApiModels.DriftMetricsResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{modelId}/history")
    @PreAuthorize("hasAuthority('ai.drift.read')")
    public ResponseEntity<List<ApiModels.DriftMetricsResponse>> getDriftHistory(
            @PathVariable UUID modelId,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        List<Models.DriftMetrics> history = driftMonitoringService.findByModelId(modelId, from, to);
        return ResponseEntity.ok(history.stream().map(ApiModels.DriftMetricsResponse::from).toList());
    }

    @GetMapping("/status/{status}")
    @PreAuthorize("hasAuthority('ai.drift.read')")
    public ResponseEntity<List<ApiModels.DriftMetricsResponse>> getByStatus(@PathVariable String status) {
        List<Models.DriftMetrics> results = driftMonitoringService.findByStatus(Models.DriftStatus.valueOf(status));
        return ResponseEntity.ok(results.stream().map(ApiModels.DriftMetricsResponse::from).toList());
    }
}
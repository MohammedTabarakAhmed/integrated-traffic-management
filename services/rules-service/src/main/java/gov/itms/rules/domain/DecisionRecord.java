package gov.itms.rules.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A logged rule decision (CC-053): which rule set and exact version decided, for which region and date, with which
 * inputs, which rules matched and what came out; who asked, through which channel, and for which business reference.
 */
public record DecisionRecord(UUID id, String ruleSetCode, UUID versionId, int versionNo, String region,
                             LocalDate date, Map<String, Object> inputs, List<Integer> matchedRules,
                             List<Map<String, Object>> outputs, String reference, String actorId, Channel channel,
                             String correlationId, Instant at) {

    public DecisionRecord {
        // inputs may carry explicit nulls (an input left empty), which Map.copyOf refuses
        inputs = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(inputs));
        matchedRules = List.copyOf(matchedRules);
        outputs = List.copyOf(outputs);
    }
}

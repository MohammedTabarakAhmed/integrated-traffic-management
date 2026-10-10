package gov.itms.licensing.application;

import gov.itms.licensing.domain.LicenceRequest;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The configurable licensing rules ({@code itms.licensing.*}, NFR-042).
 *
 * @param probationMonths  how long a first licence is probationary (DL-050; BRD default 2 years)
 * @param serviceCodes     the fees-service and hold-eligibility-service code of each paid transaction
 * @param conditionCodes   the licence conditions that may be recorded (DL-023)
 */
public record LicensingRules(int probationMonths, Map<LicenceRequest.Kind, String> serviceCodes,
                             Set<String> conditionCodes) {

    public LicensingRules {
        if (probationMonths < 0 || probationMonths > 120) {
            throw new IllegalArgumentException("itms.licensing.probation-months must be 0-120 (DL-050)");
        }
        serviceCodes = serviceCodes == null ? Map.of() : Map.copyOf(new EnumMap<>(serviceCodes));
        for (LicenceRequest.Kind kind : LicenceRequest.Kind.values()) {
            if (serviceCodes.get(kind) == null || serviceCodes.get(kind).isBlank()) {
                throw new IllegalArgumentException("itms.licensing.service-codes." + kind + " is required");
            }
        }
        conditionCodes = conditionCodes == null ? Set.of() : Set.copyOf(conditionCodes);
    }

    public String serviceCode(LicenceRequest.Kind kind) {
        return Objects.requireNonNull(serviceCodes.get(kind));
    }

    public List<String> sortedConditionCodes() {
        return conditionCodes.stream().sorted().toList();
    }
}

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
 * @param probationMonths        how long a first licence is probationary (DL-050; BRD default 2 years)
 * @param serviceCodes           the fees-service and hold-eligibility-service code of each paid transaction
 * @param conditionCodes         the licence conditions that may be recorded (DL-023)
 * @param renewalWindowDays      how long before expiry renewal is allowed (DL-051; BRD default 90 days)
 * @param eyeTestValidityDays    how old a passed eye test may be for renewal (DL-051; Q-077)
 * @param autoRenewalNoticeDays  how long after the consent notice automatic renewal runs (DL-065; Q-077)
 * @param lensesCondition        the condition recorded when an eye test needs corrective lenses (DL-007, DL-023)
 */
public record LicensingRules(int probationMonths, Map<LicenceRequest.Kind, String> serviceCodes,
                             Set<String> conditionCodes, int renewalWindowDays, int eyeTestValidityDays,
                             int autoRenewalNoticeDays, String lensesCondition) {

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
        if (renewalWindowDays < 1 || renewalWindowDays > 730) {
            throw new IllegalArgumentException("itms.licensing.renewal-window-days must be 1-730 (DL-051)");
        }
        if (eyeTestValidityDays < 1 || eyeTestValidityDays > 3650) {
            throw new IllegalArgumentException("itms.licensing.eye-test-validity-days must be 1-3650 (DL-051)");
        }
        if (autoRenewalNoticeDays < 0 || autoRenewalNoticeDays > renewalWindowDays) {
            throw new IllegalArgumentException("itms.licensing.auto-renewal.notice-days must be 0 to the renewal"
                    + " window (DL-065)");
        }
        if (lensesCondition != null && !conditionCodes.contains(lensesCondition)) {
            throw new IllegalArgumentException("itms.licensing.lenses-condition must be one of the condition codes");
        }
    }

    public String serviceCode(LicenceRequest.Kind kind) {
        return Objects.requireNonNull(serviceCodes.get(kind));
    }

    public List<String> sortedConditionCodes() {
        return conditionCodes.stream().sorted().toList();
    }
}

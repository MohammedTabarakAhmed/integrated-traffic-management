package gov.itms.vehicle.application;

import java.util.List;
import java.util.Map;

/**
 * Configurable vehicle rules (VL-011, VL-023, VL-041; NFR-042): nothing here is hard-coded policy. These move to
 * rules-service decision tables when the business publishes them (CC-050, Q-071).
 *
 * @param validityMonths          registration validity per category (VL-023; BRD default 12)
 * @param defaultValidityMonths   for categories not listed
 * @param oldVehicleYears         from this age on, {@code oldVehicleValidityMonths} applies instead (0 = never)
 * @param inspectionExemptYears   per category, years from the model year without inspection (VL-041; for example
 *                                3 for a private car)
 * @param inspectionValidityDays  how recent a passed inspection must be
 * @param renewalWindowDays       renewal opens this many days before expiry (VL-011; BRD default 30)
 * @param plateCategory           plate category per vehicle category (VL-030)
 * @param firstRegistrationFee    fees-service service code of first registration
 * @param renewalFee              fees-service service code of renewal
 * @param checkDigitRegions       first VIN characters whose VINs carry a check digit (VL-003); empty = all
 */
public record VehicleRules(Map<String, Integer> validityMonths, int defaultValidityMonths, int oldVehicleYears,
                           int oldVehicleValidityMonths, Map<String, Integer> inspectionExemptYears,
                           int defaultInspectionExemptYears, int inspectionValidityDays, int renewalWindowDays,
                           Map<String, String> plateCategory, String firstRegistrationFee, String renewalFee,
                           List<String> checkDigitRegions) {

    public VehicleRules {
        validityMonths = Map.copyOf(validityMonths);
        inspectionExemptYears = Map.copyOf(inspectionExemptYears);
        plateCategory = Map.copyOf(plateCategory);
        checkDigitRegions = List.copyOf(checkDigitRegions);
    }

    public int validityMonths(String category, int ageYears) {
        if (oldVehicleYears > 0 && ageYears >= oldVehicleYears) {
            return oldVehicleValidityMonths;
        }
        return validityMonths.getOrDefault(category, defaultValidityMonths);
    }

    public boolean inspectionNeeded(String category, int ageYears) {
        return ageYears >= inspectionExemptYears.getOrDefault(category, defaultInspectionExemptYears);
    }

    public String plateCategoryOf(String vehicleCategory) {
        return plateCategory.getOrDefault(vehicleCategory, vehicleCategory);
    }
}

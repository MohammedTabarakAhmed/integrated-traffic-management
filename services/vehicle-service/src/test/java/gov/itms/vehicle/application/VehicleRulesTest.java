package gov.itms.vehicle.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("VL-023")
public class VehicleRulesTest {

    /** PRIVATE: 12 months, 3 years without inspection; COMMERCIAL: 6 months, always inspected; old from 20 years. */
    public static VehicleRules rules() {
        return new VehicleRules(Map.of("PRIVATE", 12, "COMMERCIAL", 6), 12, 20, 6,
                Map.of("PRIVATE", 3, "COMMERCIAL", 0), 0, 30, 30, Map.of("COMMERCIAL", "TRADE"), "VEH_FIRST_REG",
                "VEH_RENEWAL", List.of("1", "2", "3", "4", "5"));
    }

    @Test
    @DisplayName("VL-023: validity by category, the default for others, and the old-vehicle rule from its age")
    void validity() {
        VehicleRules r = rules();
        assertThat(r.validityMonths("PRIVATE", 5)).isEqualTo(12);
        assertThat(r.validityMonths("COMMERCIAL", 5)).isEqualTo(6);
        assertThat(r.validityMonths("CLASSIC", 5)).isEqualTo(12);
        assertThat(r.validityMonths("PRIVATE", 20)).isEqualTo(6);
        assertThat(new VehicleRules(Map.of(), 12, 0, 6, Map.of(), 0, 30, 30, Map.of(), "F", "R", List.of())
                .validityMonths("PRIVATE", 90)).as("0 = no old-vehicle rule").isEqualTo(12);
    }

    @Test
    @Tag("VL-041")
    @DisplayName("VL-041: inspection is needed from the category's exempt age; plate category falls back to the code")
    void inspectionAndPlates() {
        VehicleRules r = rules();
        assertThat(r.inspectionNeeded("PRIVATE", 2)).isFalse();
        assertThat(r.inspectionNeeded("PRIVATE", 3)).isTrue();
        assertThat(r.inspectionNeeded("COMMERCIAL", 0)).isTrue();
        assertThat(r.inspectionNeeded("CLASSIC", 0)).as("default exempt years 0").isTrue();
        assertThat(r.plateCategoryOf("COMMERCIAL")).isEqualTo("TRADE");
        assertThat(r.plateCategoryOf("PRIVATE")).isEqualTo("PRIVATE");
    }
}

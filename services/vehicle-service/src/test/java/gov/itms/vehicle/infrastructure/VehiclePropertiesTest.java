package gov.itms.vehicle.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.vehicle.application.VehicleRules;
import gov.itms.vehicle.infrastructure.VehicleServiceConfiguration.VehicleProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("NFR-042")
@Tag("VL-023")
class VehiclePropertiesTest {

    private static final VehicleProperties.Insurance INSURER = new VehicleProperties.Insurance(
            URI.create("http://insurance-hub:8097"), Duration.ofSeconds(3));

    private static VehicleProperties props(List<VehicleProperties.CategoryRule> categories, int validity,
                                           int window, VehicleProperties.Insurance insurance) {
        return new VehicleProperties(categories, validity, 0, 12, 0, 30, window, "VEH_FIRST_REG", "VEH_RENEWAL",
                null, insurance);
    }

    @Test
    @Tag("VL-041")
    @DisplayName("VL-023, VL-041: category rules become the vehicle rules; empty check-digit regions mean all VINs")
    void rules() {
        VehicleRules r = props(List.of(new VehicleProperties.CategoryRule("PRIVATE", 12, 3, "PRIVATE"),
                new VehicleProperties.CategoryRule("COMMERCIAL", 6, 0, "TRADE")), 12, 30, INSURER).rules();
        assertThat(r.validityMonths("COMMERCIAL", 1)).isEqualTo(6);
        assertThat(r.inspectionNeeded("PRIVATE", 2)).isFalse();
        assertThat(r.plateCategoryOf("COMMERCIAL")).isEqualTo("TRADE");
        assertThat(r.renewalWindowDays()).isEqualTo(30);
        assertThat(r.checkDigitRegions()).isEmpty();
    }

    @Test
    @DisplayName("NFR-042: validity 1-60 months, window 1-180 days, valid category entries and an insurer address")
    void ranges() {
        assertThatThrownBy(() -> props(List.of(), 0, 30, INSURER)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validity");
        assertThatThrownBy(() -> props(List.of(), 61, 30, INSURER)).hasMessageContaining("validity");
        assertThatThrownBy(() -> props(List.of(), 12, 0, INSURER)).hasMessageContaining("windows");
        assertThatThrownBy(() -> props(List.of(), 12, 181, INSURER)).hasMessageContaining("windows");
        assertThatThrownBy(() -> props(List.of(new VehicleProperties.CategoryRule("private", 12, 3, null)), 12, 30,
                INSURER)).hasMessageContaining("categories");
        assertThatThrownBy(() -> props(List.of(new VehicleProperties.CategoryRule("PRIVATE", 61, 3, null)), 12, 30,
                INSURER)).hasMessageContaining("categories");
        assertThatThrownBy(() -> props(List.of(), 12, 30, new VehicleProperties.Insurance(null,
                Duration.ofSeconds(3)))).hasMessageContaining("insurance.base-url");
    }

    @Test
    @Tag("VL-004")
    @DisplayName("VL-004: the default category list carries all twelve BRD categories with their rules")
    void defaultCategories() throws Exception {
        String yml = java.nio.file.Files.readString(java.nio.file.Path.of(
                System.getProperty("user.dir"), "src", "main", "resources", "application.yml"));
        String block = yml.substring(yml.indexOf("categories:"),
                yml.indexOf("# Increment 2 transactions"));
        assertThat(List.of("PRIVATE", "COMMERCIAL", "PUBLIC_TRANSPORT", "RENTAL", "GOVERNMENT", "MILITARY",
                "SECURITY_SERVICES", "DIPLOMATIC", "TRIAL", "EXPORT", "CLASSIC", "HEAVY_EQUIPMENT"))
                .allSatisfy(code -> assertThat(block).contains("code: " + code));
        assertThat(block).contains("validity-months").contains("inspection-exempt-years").contains("plate-category");
    }
}

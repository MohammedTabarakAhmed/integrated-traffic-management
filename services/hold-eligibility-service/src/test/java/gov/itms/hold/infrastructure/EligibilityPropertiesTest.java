package gov.itms.hold.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.hold.domain.EligibilityPolicy;
import gov.itms.hold.infrastructure.EligibilityProperties.Disclosed;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

@Tag("CC-032")
class EligibilityPropertiesTest {

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private static Properties bundle(String name) throws IOException {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new ClassPathResource(name).getInputStream(), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    /** The disclosed hold types as configured in application.yml. */
    private static List<Disclosed> configured() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("app",
                new ClassPathResource("application.yml"));
        List<Disclosed> list = new ArrayList<>();
        for (int i = 0; ; i++) {
            Object type = sources.get(0).getProperty("itms.eligibility.disclosed-hold-types[" + i + "].hold-type");
            if (type == null) {
                return list;
            }
            list.add(new Disclosed(type.toString(), sources.get(0)
                    .getProperty("itms.eligibility.disclosed-hold-types[" + i + "].next-step").toString()));
        }
    }

    @Test
    @DisplayName("CC-032/Q-053: the configured policy masks sensitive types and every text exists in both languages")
    void configuredPolicyAndTexts() throws IOException {
        EligibilityPolicy policy = new EligibilityProperties(configured()).toPolicy(messages());
        assertThat(policy.disclosedHoldTypes()).containsOnlyKeys("UNPAID_FINES", "EXPIRED_INSURANCE",
                "FAILED_INSPECTION", "MORTGAGE", "IMPOUND", "DRIVING_BAN");
        for (String masked : List.of("COURT_ORDER", "POLICE_CIRCULAR", "ADMINISTRATIVE_BAN", "DECEASED",
                "EXTERNAL_TRAVEL_RESTRICTION")) {
            assertThat(policy.blockFor(masked).reasonCode()).as(masked).isEqualTo(EligibilityPolicy.RESTRICTED);
        }
        Set<String> english = keys(bundle("messages.properties"));
        Set<String> arabic = keys(bundle("messages_ar.properties"));
        assertThat(arabic).as("every customer text also in the national language (CC-010)").isEqualTo(english);
        assertThat(english).contains(EligibilityPolicy.REASON_KEY + "RESTRICTED",
                EligibilityPolicy.NEXT_STEP_KEY + "CONTACT_AUTHORITY");
    }

    private static Set<String> keys(Properties p) {
        return p.stringPropertyNames().stream().filter(k -> k.startsWith("eligibility."))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("CC-032: start-up is refused for a repeated, incomplete or malformed entry, or one without a text")
    void badConfigurationIsRefused() {
        assertThat(new EligibilityProperties(null).toPolicy(messages()).disclosedHoldTypes()).isEmpty();
        Map<String, List<Disclosed>> bad = Map.of(
                "twice", List.of(new Disclosed("UNPAID_FINES", "PAY_FINES"), new Disclosed("UNPAID_FINES", "PAY_FINES")),
                "no step", List.of(new Disclosed("UNPAID_FINES", null)),
                "malformed", List.of(new Disclosed("unpaid", "PAY_FINES")),
                "no reason text", List.of(new Disclosed("DECEASED", "CONTACT_AUTHORITY")),
                "no step text", List.of(new Disclosed("UNPAID_FINES", "GO_HOME")));
        bad.forEach((name, list) -> assertThatThrownBy(() -> new EligibilityProperties(list).toPolicy(messages()))
                .as(name).isInstanceOf(IllegalStateException.class));
    }
}

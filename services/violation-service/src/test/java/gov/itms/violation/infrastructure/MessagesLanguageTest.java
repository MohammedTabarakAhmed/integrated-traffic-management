package gov.itms.violation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * CC-013: adding a language must need no code change. This test is the code-path proof: it reads the
 * same basename the service serves from ("messages"; see spring.messages.basename) and finds a
 * bundled French file that exists only as a resource file on the classpath — messages_fr.properties
 * under src/test/resources, added together with this test and nothing else.
 */
@Tag("CC-013")
class MessagesLanguageTest {

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Test
    @DisplayName("CC-013: a language added as messages_xx.properties is served without any code change")
    void newLanguageIsAResourceFileOnly() {
        String french = messages().getMessage("problem.REASON_REQUIRED", null, Locale.FRENCH);
        assertThat(french).isNotEqualTo(messages().getMessage("problem.REASON_REQUIRED", null, Locale.ENGLISH));
    }

    @Test
    @DisplayName("CC-013: a partial language bundle falls back to the default bundle for its missing keys")
    void partialBundleFallsBack() {
        String key = "problem.REASON_TOO_LONG"; // present in messages.properties, absent in messages_fr.properties
        assertThat(messages().getMessage(key, null, Locale.FRENCH))
                .isEqualTo(messages().getMessage(key, null, Locale.ENGLISH));
    }

    @Test
    @DisplayName("CC-013: a locale with no bundle at all falls back to the default bundle")
    void unknownLocaleFallsBack() {
        assertThat(messages().getMessage("problem.REASON_REQUIRED", null, Locale.forLanguageTag("de-DE")))
                .isEqualTo(messages().getMessage("problem.REASON_REQUIRED", null, Locale.ENGLISH));
    }
}

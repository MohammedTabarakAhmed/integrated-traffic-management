package gov.itms.platform.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Ranges of the event relay settings (NFR-042, ADR-040). */
@Tag("NFR-042")
class EventPropertiesTest {

    @Test
    void domainTopicDefaultsToTheServiceTopic() {
        var properties = new EventProperties(true, "itms.audit.v1", "", 100, Duration.ofSeconds(10));
        assertThat(properties.domainTopicFor("hold-eligibility-service"))
                .isEqualTo("itms.hold-eligibility-service.events.v1");
        assertThat(new EventProperties(true, "a", "custom.topic", 100, Duration.ofSeconds(10))
                .domainTopicFor("x")).isEqualTo("custom.topic");
    }

    @Test
    void batchSizeAndTimeoutAreBounded() {
        assertThatThrownBy(() -> new EventProperties(true, "a", "", 0, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventProperties(true, "a", "", 1001, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventProperties(true, "a", "", 100, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventProperties(true, "a", "", 100, Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

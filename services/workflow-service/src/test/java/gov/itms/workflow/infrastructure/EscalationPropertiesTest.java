package gov.itms.workflow.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.workflow.infrastructure.WorkflowServiceConfiguration.EscalationProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("NFR-042")
@Tag("CC-062")
class EscalationPropertiesTest {

    @Test
    @DisplayName("NFR-042: the escalation interval is 10 s to 1 h and the batch size 1-1000")
    void ranges() {
        assertThat(new EscalationProperties(true, Duration.ofSeconds(10), 1).batchSize()).isEqualTo(1);
        assertThat(new EscalationProperties(true, Duration.ofHours(1), 1000).interval()).isEqualTo(Duration.ofHours(1));
        assertThatThrownBy(() -> new EscalationProperties(true, Duration.ofSeconds(9), 100))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("interval");
        assertThatThrownBy(() -> new EscalationProperties(true, Duration.ofMinutes(61), 100))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("interval");
        assertThatThrownBy(() -> new EscalationProperties(true, null, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EscalationProperties(true, Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new EscalationProperties(true, Duration.ofMinutes(1), 1001))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
    }
}

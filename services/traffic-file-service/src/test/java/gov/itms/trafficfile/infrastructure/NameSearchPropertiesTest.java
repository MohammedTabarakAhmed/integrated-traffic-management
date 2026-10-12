package gov.itms.trafficfile.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-101")
class NameSearchPropertiesTest {

    @Test
    @DisplayName("name search settings stay within their ranges")
    void ranges() {
        assertThat(new NameSearchProperties(20, 3).maxResults()).isEqualTo(20);
        assertThatThrownBy(() -> new NameSearchProperties(0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NameSearchProperties(101, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NameSearchProperties(20, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NameSearchProperties(20, 7)).isInstanceOf(IllegalArgumentException.class);
    }
}

package gov.itms.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ItmsPlatformAutoConfigurationTest {

    @Test
    @Tag("CC-120")
    @DisplayName("CC-120: the platform clock is UTC and ticks in microseconds, the precision the database stores")
    void clockTicksInMicroseconds() {
        Clock clock = new ItmsPlatformAutoConfiguration().itmsClock();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        for (int i = 0; i < 1_000; i++) {
            assertThat(clock.instant().getNano() % 1_000).isZero();
        }
    }
}

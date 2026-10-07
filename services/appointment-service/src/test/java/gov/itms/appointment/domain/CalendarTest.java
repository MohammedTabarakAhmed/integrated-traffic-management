package gov.itms.appointment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-092")
public class CalendarTest {

    public static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");
    /** Monday 2026-10-12. */
    public static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    static Calendar.Period period(String opens, String closes) {
        return new Calendar.Period(LocalTime.parse(opens), LocalTime.parse(closes));
    }

    /** Open Monday-Friday 08:00-12:00 and 13:00-15:00 in {@code zone}. */
    public static Calendar.Centre centre(String code, ZoneId zone) {
        List<Calendar.Period> day = List.of(period("13:00", "15:00"), period("08:00", "12:00"));
        return new Calendar.Centre(code, "NORTH", zone, Map.of(DayOfWeek.MONDAY, day, DayOfWeek.TUESDAY, day,
                DayOfWeek.WEDNESDAY, day, DayOfWeek.THURSDAY, day, DayOfWeek.FRIDAY, day), true);
    }

    private static ZonedDateTime farPast(ZoneId zone) {
        return ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, zone);
    }

    @Test
    @DisplayName("CC-092: periods are sorted, cut into slot lengths, and a slot never runs past closing")
    void periodsAndSlots() {
        Calendar.Centre c = centre("DXB_1", DUBAI);
        assertThat(c.hours(DayOfWeek.MONDAY).getFirst().opens()).isEqualTo(LocalTime.of(8, 0));
        List<ZonedDateTime> starts = Calendar.slotStarts(c, MONDAY, Duration.ofMinutes(90), List.of(), "R1",
                farPast(DUBAI));
        assertThat(starts).extracting(z -> z.toLocalTime().toString())
                .containsExactly("08:00", "09:30", "13:00");
        assertThat(Calendar.slotStarts(c, MONDAY.plusDays(5), Duration.ofMinutes(30), List.of(), "R1",
                farPast(DUBAI))).as("Saturday is closed").isEmpty();
    }

    @Test
    @DisplayName("CC-092: nothing before 'not before'; holidays close the centre, blackouts can close one resource")
    void notBeforeAndClosures() {
        Calendar.Centre c = centre("DXB_1", DUBAI);
        ZonedDateTime tenOClock = MONDAY.atTime(10, 0).atZone(DUBAI);
        assertThat(Calendar.slotStarts(c, MONDAY, Duration.ofHours(1), List.of(), "R1", tenOClock))
                .extracting(z -> z.toLocalTime().toString()).containsExactly("10:00", "11:00", "13:00", "14:00");

        Calendar.Closure holiday = new Calendar.Closure(0, "DXB_1", null, MONDAY, MONDAY, Calendar.ClosureKind.HOLIDAY,
                "National day");
        Calendar.Closure laneBlackout = new Calendar.Closure(0, "DXB_1", "LANE_2", MONDAY, MONDAY.plusDays(1),
                Calendar.ClosureKind.BLACKOUT, "Lane maintenance");
        assertThat(Calendar.slotStarts(c, MONDAY, Duration.ofHours(1), List.of(holiday), "R1", farPast(DUBAI)))
                .isEmpty();
        assertThat(Calendar.slotStarts(c, MONDAY.plusDays(1), Duration.ofHours(1), List.of(laneBlackout), "LANE_2",
                farPast(DUBAI))).isEmpty();
        assertThat(Calendar.slotStarts(c, MONDAY.plusDays(1), Duration.ofHours(1), List.of(laneBlackout), "LANE_1",
                farPast(DUBAI))).hasSize(6);
    }

    @Test
    @DisplayName("CC-092: slot instants follow the centre's zone, also on a daylight-saving change day")
    void zonesAndDaylightSaving() {
        Calendar.Centre dubai = centre("DXB_1", DUBAI);
        assertThat(Calendar.slotStarts(dubai, MONDAY, Duration.ofHours(1), List.of(), "R1", farPast(DUBAI))
                .getFirst().toInstant()).isEqualTo(Instant.parse("2026-10-12T04:00:00Z"));

        ZoneId london = ZoneId.of("Europe/London");
        LocalDate before = LocalDate.of(2026, 3, 27);   // Friday, GMT
        LocalDate after = LocalDate.of(2026, 3, 30);    // Monday, BST
        Calendar.Centre uk = centre("LON_1", london);
        assertThat(Calendar.slotStarts(uk, before, Duration.ofHours(1), List.of(), "R1", farPast(london)).getFirst()
                .toInstant()).isEqualTo(Instant.parse("2026-03-27T08:00:00Z"));
        assertThat(Calendar.slotStarts(uk, after, Duration.ofHours(1), List.of(), "R1", farPast(london)).getFirst()
                .toInstant()).isEqualTo(Instant.parse("2026-03-30T07:00:00Z"));

        // Sunday 2026-03-29: 01:00-02:00 local does not exist. A night period across the gap must not give two slots
        // at the same instant, nor a slot at a local time that never happened.
        Calendar.Centre night = new Calendar.Centre("LON_N", "NORTH", london,
                Map.of(DayOfWeek.SUNDAY, List.of(period("00:00", "04:00"))), true);
        List<ZonedDateTime> starts = Calendar.slotStarts(night, LocalDate.of(2026, 3, 29), Duration.ofHours(1),
                List.of(), "R1", farPast(london));
        assertThat(starts.stream().map(ZonedDateTime::toInstant).distinct().count())
                .as("distinct instants").isEqualTo(starts.size());
        assertThat(starts).extracting(ZonedDateTime::toInstant).isSorted();
    }

    @Test
    @DisplayName("CC-090, CC-091, CC-092: overlapping hours, bad closures, capacities and settings are refused")
    void validation() {
        assertThatThrownBy(() -> period("10:00", "10:00")).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("HOURS_INVALID");
        assertThatThrownBy(() -> new Calendar.Centre("C1", "NORTH", DUBAI, Map.of(DayOfWeek.MONDAY,
                List.of(period("08:00", "12:00"), period("11:00", "13:00"))), true))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("HOURS_INVALID");
        assertThatThrownBy(() -> new Calendar.Closure(0, "C1", null, MONDAY, MONDAY.minusDays(1),
                Calendar.ClosureKind.HOLIDAY, "x")).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("CLOSURE_INVALID");
        assertThatThrownBy(() -> new Calendar.Closure(0, "C1", null, MONDAY, MONDAY.plusYears(2).plusDays(1),
                Calendar.ClosureKind.BLACKOUT, "x")).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("CLOSURE_INVALID");
        assertThatThrownBy(() -> new Calendar.Resource("R1", "C1", Calendar.ResourceType.EXAMINER, "Examiner",
                Set.of("DL_TEST"), 0, true)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("CAPACITY_INVALID");
        assertThatThrownBy(() -> new Calendar.Resource("R1", "C1", Calendar.ResourceType.EXAMINER, "Examiner",
                Set.of(), 1, true)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Calendar.ServiceSettings("DL_TEST", Duration.ofMinutes(4), 30,
                Duration.ofHours(24), 2, 0, 90, 0, null)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SETTINGS_INVALID");
        assertThatThrownBy(() -> new Calendar.ServiceSettings("DL_TEST", Duration.ofSeconds(1830), 30,
                Duration.ofHours(24), 2, 0, 90, 0, null)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SETTINGS_INVALID");
        assertThatThrownBy(() -> new Calendar.ServiceSettings("DL_TEST", Duration.ofMinutes(30), 366,
                Duration.ofHours(24), 2, 0, 90, 0, null)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SETTINGS_INVALID");
        assertThatThrownBy(() -> new Calendar.ServiceSettings("DL_TEST", Duration.ofMinutes(30), 30,
                Duration.ofHours(24), 21, 0, 90, 0, null)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SETTINGS_INVALID");
    }
}

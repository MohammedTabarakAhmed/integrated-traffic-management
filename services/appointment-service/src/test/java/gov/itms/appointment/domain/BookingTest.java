package gov.itms.appointment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-091")
class BookingTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final Instant START = NOW.plus(Duration.ofDays(3));
    /** BRD example: two changes; changes close 24 hours before. */
    private static final Calendar.ServiceSettings SETTINGS = new Calendar.ServiceSettings("DL_TEST",
            Duration.ofMinutes(30), 30, Duration.ofHours(24), 2, 2, 90, 30, "NO_SHOW_FEE");

    private static Booking booked() {
        return Booking.book(UUID.randomUUID(), "DL_TEST", "DXB_1", "R1", "c-1", "TXN-1", START,
                START.plus(Duration.ofMinutes(30)), NOW);
    }

    @Test
    @DisplayName("CC-091: a booking changes at most the configured number of times")
    void changeLimit() {
        Booking b = booked();
        b.reminderSent(NOW);
        b.change("R2", START.plusSeconds(3600), START.plusSeconds(5400), SETTINGS, NOW);
        assertThat(b.resourceCode()).isEqualTo("R2");
        assertThat(b.changes()).isEqualTo(1);
        assertThat(b.reminderSentAt()).as("a moved appointment is reminded again").isNull();
        b.change("R1", START, START.plusSeconds(1800), SETTINGS, NOW);
        assertThatThrownBy(() -> b.change("R1", START.plusSeconds(1800), START.plusSeconds(3600), SETTINGS, NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("CHANGE_LIMIT_REACHED");
        assertThat(b.start()).isEqualTo(START);
    }

    @Test
    @DisplayName("CC-091: customers change and cancel only before the lead time")
    void leadTime() {
        Booking b = booked();
        Instant late = START.minus(Duration.ofHours(23));
        assertThatThrownBy(() -> b.change("R1", START.plusSeconds(1800), START.plusSeconds(3600), SETTINGS, late))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("TOO_LATE_TO_CHANGE");
        assertThatThrownBy(() -> b.cancel(false, SETTINGS.minLeadTime(), late))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("TOO_LATE_TO_CHANGE");
        b.cancel(false, SETTINGS.minLeadTime(), START.minus(Duration.ofHours(24)));
        assertThat(b.status()).isEqualTo(Booking.Status.CANCELLED);
    }

    @Test
    @DisplayName("CC-091: staff cancel at any time before the start, not after it")
    void staffCancel() {
        Booking b = booked();
        b.cancel(true, SETTINGS.minLeadTime(), START.minusSeconds(60));
        assertThat(b.status()).isEqualTo(Booking.Status.CANCELLED);
        assertThat(b.closedAt()).isEqualTo(START.minusSeconds(60));
        Booking started = booked();
        assertThatThrownBy(() -> started.cancel(true, SETTINGS.minLeadTime(), START))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("BOOKING_STARTED");
    }

    @Test
    @Tag("CC-093")
    @DisplayName("CC-093: attendance is recorded from the start; closed bookings never change")
    void attendance() {
        Booking b = booked();
        assertThatThrownBy(() -> b.attendance(true, START.minusSeconds(1))).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("BOOKING_NOT_STARTED");
        b.attendance(false, START.plusSeconds(600));
        assertThat(b.status()).isEqualTo(Booking.Status.NO_SHOW);
        assertThatThrownBy(() -> b.attendance(true, START.plusSeconds(700))).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("BOOKING_CLOSED");
        assertThatThrownBy(() -> b.cancel(true, SETTINGS.minLeadTime(), START)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("BOOKING_CLOSED");
        Booking came = booked();
        came.attendance(true, START);
        assertThat(came.status()).isEqualTo(Booking.Status.ATTENDED);
    }

    @Test
    @DisplayName("CC-091: customer ids and transaction references are checked")
    void fields() {
        assertThatThrownBy(() -> Booking.book(UUID.randomUUID(), "DL_TEST", "DXB_1", "R1", "c 1", null, START,
                START, NOW)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> Booking.book(UUID.randomUUID(), "DL_TEST", "DXB_1", "R1", "c-1", "txn-1", START,
                START, NOW)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
    }
}

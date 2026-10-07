package gov.itms.appointment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.appointment.domain.Actor;
import gov.itms.appointment.domain.Booking;
import gov.itms.appointment.domain.Calendar;
import gov.itms.appointment.domain.CalendarTest;
import gov.itms.appointment.domain.Channel;
import gov.itms.appointment.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-090")
class AppointmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final Instant MONDAY_8 = CalendarTest.MONDAY.atTime(8, 0).atZone(CalendarTest.DUBAI).toInstant();

    private final MutableClock clock = new MutableClock(NOW);
    private final Repo repo = new Repo();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final AppointmentService service = new AppointmentService(repo, new AuditRecorder() {
        @Override
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            audit.add(action + " " + entityId);
        }

        @Override
        public void recordAs(CurrentActor actor, String action, String entityType, String entityId, Object oldValue,
                             Object newValue, String reason) {
            record(action, entityType, entityId, oldValue, newValue, reason);
        }
    }, new DomainEventRecorder() {
        @Override
        public void record(String eventType, String subject, Object data) {
            events.add(eventType + " " + subject);
        }

        @Override
        public void recordAs(CurrentActor actor, String eventType, String subject, Object data) {
            record(eventType, subject, data);
        }
    }, clock);

    private static Caller caller(String id, Set<String> regions, String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor(id, id), Channel.BACKOFFICE, new Caller.Grants() {
            @Override
            public boolean allowsInRegion(String permission, String region) {
                return held.contains(permission) && (regions.contains("*") || regions.contains(region));
            }

            @Override
            public boolean holds(String permission) {
                return held.contains(permission);
            }
        });
    }

    private static final Caller ADMIN = caller("u-admin", Set.of("NORTH"), AppointmentService.CALENDAR_MANAGE);
    private static final Caller AGENT = caller("u-agent", Set.of("NORTH"), AppointmentService.BOOK,
            AppointmentService.ATTENDANCE, AppointmentService.READ);
    private static final Caller SOUTH_AGENT = caller("u-south", Set.of("SOUTH"), AppointmentService.BOOK,
            AppointmentService.ATTENDANCE, AppointmentService.READ);

    private static Caller customer(String id) {
        return caller(id, Set.of());
    }

    @BeforeEach
    void calendar() {
        service.saveCentre(CalendarTest.centre("DXB_1", CalendarTest.DUBAI), ADMIN);
        service.saveResource(new Calendar.Resource("EXAMINER_1", "DXB_1", Calendar.ResourceType.EXAMINER, "Examiner 1",
                Set.of("DL_TEST"), 1, true), ADMIN);
        service.saveResource(new Calendar.Resource("EXAMINER_2", "DXB_1", Calendar.ResourceType.EXAMINER, "Examiner 2",
                Set.of("DL_TEST"), 1, true), ADMIN);
        service.saveSettings(new Calendar.ServiceSettings("DL_TEST", Duration.ofMinutes(30), 30, Duration.ofHours(24),
                2, 2, 90, 30, "NO_SHOW_FEE"), ADMIN);
    }

    @Test
    @Tag("CC-092")
    @DisplayName("CC-090, CC-092: each slot is offered while a resource has capacity; a full slot moves to the next one")
    void capacity() {
        assertThat(service.slots("DXB_1", "DL_TEST", CalendarTest.MONDAY, CalendarTest.MONDAY))
                .filteredOn(s -> s.start().toInstant().equals(MONDAY_8)).hasSize(2);
        Booking first = service.book("DL_TEST", "DXB_1", null, MONDAY_8, "c-1", null, customer("c-1"));
        Booking second = service.book("DL_TEST", "DXB_1", null, MONDAY_8, "c-2", null, customer("c-2"));
        assertThat(List.of(first.resourceCode(), second.resourceCode())).containsExactly("EXAMINER_1", "EXAMINER_2");
        assertThat(service.slots("DXB_1", "DL_TEST", CalendarTest.MONDAY, CalendarTest.MONDAY))
                .noneMatch(s -> s.start().toInstant().equals(MONDAY_8));
        assertThatThrownBy(() -> service.book("DL_TEST", "DXB_1", null, MONDAY_8, "c-3", null, customer("c-3")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SLOT_NOT_AVAILABLE");
        assertThatThrownBy(() -> service.book("DL_TEST", "DXB_1", null, MONDAY_8.plusSeconds(60), "c-3", null,
                customer("c-3"))).as("not a slot start").isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SLOT_NOT_AVAILABLE");
        assertThatThrownBy(() -> service.book("DL_TEST", "DXB_1", null, MONDAY_8.plusSeconds(1800), "c-1", null,
                customer("c-1"))).isInstanceOf(DomainException.class).extracting("code").isEqualTo("BOOKING_EXISTS");
        assertThatThrownBy(() -> service.slots("DXB_1", "DL_TEST", CalendarTest.MONDAY,
                CalendarTest.MONDAY.plusDays(31))).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("RANGE_INVALID");
        assertThat(events).contains("itms.appointment.booked " + first.id());
    }

    @Test
    @Tag("CC-093")
    @DisplayName("CC-093: repeated no-shows block online booking for the penalty period; staff can still book")
    void noShowPenalty() {
        for (int week = 0; week < 2; week++) {
            Instant start = MONDAY_8.plus(Duration.ofDays(7L * week));
            clock.set(start.minus(Duration.ofDays(3)));
            Booking b = service.book("DL_TEST", "DXB_1", null, start, "c-1", null, customer("c-1"));
            clock.set(start.plus(Duration.ofMinutes(10)));
            service.attendance(b.id(), false, AGENT);
        }
        assertThat(events).anyMatch(e -> e.startsWith("itms.appointment.no-show "));
        Instant next = MONDAY_8.plus(Duration.ofDays(21));
        clock.set(next.minus(Duration.ofDays(3)));
        assertThatThrownBy(() -> service.book("DL_TEST", "DXB_1", null, next, "c-1", null, customer("c-1")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("BOOKING_PENALTY");
        assertThat(service.book("DL_TEST", "DXB_1", null, next, "c-1", null, AGENT).status())
                .isEqualTo(Booking.Status.BOOKED);
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: staff act only in their regions; customers only on their own bookings")
    void regionalScope() {
        assertThatThrownBy(() -> service.saveCentre(CalendarTest.centre("SHJ_1", CalendarTest.DUBAI),
                caller("u-x", Set.of("SOUTH"), AppointmentService.CALENDAR_MANAGE)))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.book("DL_TEST", "DXB_1", null, MONDAY_8, "c-1", null, SOUTH_AGENT))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        Booking b = service.book("DL_TEST", "DXB_1", null, MONDAY_8, "c-1", null, AGENT);
        assertThatThrownBy(() -> service.get(b.id(), customer("c-2"))).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.cancel(b.id(), "not mine", customer("c-2")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.attendance(b.id(), true, SOUTH_AGENT)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThat(service.get(b.id(), customer("c-1")).id()).isEqualTo(b.id());
        assertThat(service.get(b.id(), AGENT).id()).isEqualTo(b.id());
        assertThat(audit).contains("APPOINTMENT_VIEWED " + b.id());
        assertThatThrownBy(() -> service.saveCentre(new Calendar.Centre("DXB_1", "SOUTH", CalendarTest.DUBAI,
                Map.of(), true), caller("u-x", Set.of("*"), AppointmentService.CALENDAR_MANAGE)))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("CENTRE_REGION_FIXED");
    }

    @Test
    @Tag("CC-091")
    @DisplayName("CC-091: a change moves to a free slot and counts; a closure removes the day's slots")
    void changeAndClosure() {
        Booking b = service.book("DL_TEST", "DXB_1", "EXAMINER_1", MONDAY_8, "c-1", null, customer("c-1"));
        Booking moved = service.change(b.id(), null, MONDAY_8.plus(Duration.ofHours(1)), customer("c-1"));
        assertThat(moved.changes()).isEqualTo(1);
        assertThat(moved.resourceCode()).isEqualTo("EXAMINER_1");
        service.addClosure(new Calendar.Closure(0, "DXB_1", null, CalendarTest.MONDAY.plusDays(1),
                CalendarTest.MONDAY.plusDays(1), Calendar.ClosureKind.HOLIDAY, "Holiday"), ADMIN);
        assertThat(service.slots("DXB_1", "DL_TEST", CalendarTest.MONDAY.plusDays(1),
                CalendarTest.MONDAY.plusDays(1))).isEmpty();
        Instant tuesday = MONDAY_8.plus(Duration.ofDays(1));
        assertThatThrownBy(() -> service.change(b.id(), null, tuesday, customer("c-1")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SLOT_NOT_AVAILABLE");
        assertThat(events).contains("itms.appointment.closure-added DXB_1");
    }

    @Test
    @Tag("CC-071")
    @DisplayName("reminders are emitted once per booking within the lead time")
    void remindersOnce() {
        Booking b = service.book("DL_TEST", "DXB_1", null, MONDAY_8, "c-1", null, customer("c-1"));
        assertThat(service.sendReminders(Duration.ofHours(24), 100)).isZero();
        clock.set(MONDAY_8.minus(Duration.ofHours(23)));
        assertThat(service.sendReminders(Duration.ofHours(24), 100)).isEqualTo(1);
        assertThat(service.sendReminders(Duration.ofHours(24), 100)).isZero();
        assertThat(events.stream().filter(e -> e.equals("itms.appointment.reminder-due " + b.id())).count())
                .isEqualTo(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class Repo implements AppointmentRepository {
        final Map<String, Calendar.Centre> centres = new HashMap<>();
        final List<Calendar.Closure> closures = new ArrayList<>();
        final Map<String, Calendar.Resource> resources = new LinkedHashMap<>();
        final Map<String, Calendar.ServiceSettings> settings = new HashMap<>();
        final Map<UUID, Booking> bookings = new LinkedHashMap<>();

        @Override
        public void saveCentre(Calendar.Centre centre) {
            centres.put(centre.code(), centre);
        }

        @Override
        public Optional<Calendar.Centre> findCentre(String code) {
            return Optional.ofNullable(centres.get(code));
        }

        @Override
        public long insertClosure(Calendar.Closure closure) {
            closures.add(closure);
            return closures.size();
        }

        @Override
        public List<Calendar.Closure> closures(String centreCode, LocalDate from, LocalDate to) {
            return closures.stream().filter(c -> c.centreCode().equals(centreCode) && !c.to().isBefore(from)
                    && !c.from().isAfter(to)).toList();
        }

        @Override
        public void saveResource(Calendar.Resource resource) {
            resources.put(resource.code(), resource);
        }

        @Override
        public Optional<Calendar.Resource> findResource(String code) {
            return Optional.ofNullable(resources.get(code));
        }

        @Override
        public List<Calendar.Resource> resources(String centreCode) {
            return resources.values().stream().filter(r -> r.centreCode().equals(centreCode)).toList();
        }

        @Override
        public void saveSettings(Calendar.ServiceSettings s) {
            settings.put(s.serviceCode(), s);
        }

        @Override
        public Optional<Calendar.ServiceSettings> findSettings(String serviceCode) {
            return Optional.ofNullable(settings.get(serviceCode));
        }

        @Override
        public void lockSlot(String resourceCode, Instant start) {
        }

        @Override
        public Map<String, Integer> bookedCounts(String centreCode, Instant from, Instant to) {
            Map<String, Integer> m = new HashMap<>();
            bookings.values().stream().filter(b -> b.status() == Booking.Status.BOOKED
                            && b.centreCode().equals(centreCode) && !b.start().isBefore(from) && b.start().isBefore(to))
                    .forEach(b -> m.merge(AppointmentRepository.slotKey(b.resourceCode(), b.start()), 1, Integer::sum));
            return m;
        }

        @Override
        public int bookedCount(String resourceCode, Instant start) {
            return (int) bookings.values().stream().filter(b -> b.status() == Booking.Status.BOOKED
                    && b.resourceCode().equals(resourceCode) && b.start().equals(start)).count();
        }

        @Override
        public void insertBooking(Booking booking) {
            bookings.put(booking.id(), booking);
        }

        @Override
        public Optional<Booking> findBooking(UUID id) {
            return Optional.ofNullable(bookings.get(id));
        }

        @Override
        public Optional<Booking> findBookingForUpdate(UUID id) {
            return findBooking(id);
        }

        @Override
        public void updateBooking(Booking booking) {
        }

        @Override
        public boolean activeBookingExists(String customerId, String serviceCode, String transactionReference,
                                           Instant now) {
            return bookings.values().stream().anyMatch(b -> b.status() == Booking.Status.BOOKED
                    && b.customerId().equals(customerId) && b.serviceCode().equals(serviceCode)
                    && b.start().isAfter(now));
        }

        @Override
        public List<Booking> byCustomer(String customerId, int limit) {
            return bookings.values().stream().filter(b -> b.customerId().equals(customerId)).limit(limit).toList();
        }

        @Override
        public List<Booking> byCentre(String centreCode, Instant from, Instant to, int limit) {
            return bookings.values().stream().filter(b -> b.centreCode().equals(centreCode)
                    && !b.start().isBefore(from) && b.start().isBefore(to)).limit(limit).toList();
        }

        @Override
        public List<Booking> noShows(String customerId, String serviceCode, Instant since) {
            return bookings.values().stream().filter(b -> b.status() == Booking.Status.NO_SHOW
                            && b.customerId().equals(customerId) && b.serviceCode().equals(serviceCode)
                            && !b.closedAt().isBefore(since))
                    .sorted(Comparator.comparing(Booking::closedAt).reversed()).toList();
        }

        @Override
        public List<Booking> dueForReminder(Instant now, Instant until, int limit) {
            return bookings.values().stream().filter(b -> b.status() == Booking.Status.BOOKED
                    && b.reminderSentAt() == null && b.start().isBefore(until) && b.start().isAfter(now))
                    .limit(limit).toList();
        }
    }
}

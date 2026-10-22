package gov.itms.appointment.application;

import gov.itms.appointment.domain.Booking;
import gov.itms.appointment.domain.Calendar;
import gov.itms.appointment.domain.DomainException;
import gov.itms.appointment.domain.QueueTicket;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appointments (CC-090..CC-093; ADR-052).
 * <ul>
 *   <li>calendar administrators keep each centre's working hours, holidays and blackouts, its resources and their
 *       capacity, and each service's booking settings ({@value #CALENDAR_MANAGE}, in the centre's region; CC-090,
 *       CC-092);</li>
 *   <li>anyone signed in sees free slots; customers book, change (at most the configured number of times) and cancel
 *       their own appointments before the lead time; staff book for customers ({@value #BOOK}; CC-091);</li>
 *   <li>staff record attendance ({@value #ATTENDANCE}); repeated no-shows block online booking for the penalty
 *       period and emit an event with the penalty fee code (CC-093);</li>
 *   <li>a job emits {@code itms.appointment.reminder-due} before each appointment, for notification-service.</li>
 * </ul>
 */
public class AppointmentService {

    public static final String CALENDAR_MANAGE = "appointment.calendar.manage";
    public static final String BOOK = "appointment.book";
    public static final String ATTENDANCE = "appointment.attendance";
    public static final String QUEUE_MANAGE = "appointment.queue.manage";
    public static final String READ = "appointment.read";
    private static final int MAX_SLOT_DAYS = 31;
    private static final DateTimeFormatter YY_MM_DD = DateTimeFormatter.ofPattern("yyMMdd");

    private final AppointmentRepository repo;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public AppointmentService(AppointmentRepository repo, AuditRecorder audit, DomainEventRecorder events,
                              Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ---- calendars (CC-090, CC-092)

    @Transactional
    public Calendar.Centre saveCentre(Calendar.Centre centre, Caller caller) {
        channelOf(caller);
        requireRegion(caller, CALENDAR_MANAGE, centre.region());
        repo.findCentre(centre.code()).ifPresent(old -> {
            if (!old.region().equals(centre.region())) {
                throw DomainException.validation("CENTRE_REGION_FIXED", "a centre's region does not change",
                        List.of("CC-092"));
            }
        });
        repo.saveCentre(centre);
        audit.record("APPOINTMENT_CENTRE_SAVED", "AppointmentCentre", centre.code(), null,
                Map.of("region", centre.region(), "zone", centre.zone().getId(), "active", centre.active()), null);
        return centre;
    }

    @Transactional(readOnly = true)
    public Calendar.Centre centre(String code) {
        return repo.findCentre(code).orElseThrow(() -> notFound("CENTRE_NOT_FOUND", code));
    }

    @Transactional
    public Calendar.Closure addClosure(Calendar.Closure closure, Caller caller) {
        channelOf(caller);
        Calendar.Centre c = centre(closure.centreCode());
        requireRegion(caller, CALENDAR_MANAGE, c.region());
        long id = repo.insertClosure(closure);
        Calendar.Closure saved = new Calendar.Closure(id, closure.centreCode(), closure.resourceCode(),
                closure.from(), closure.to(), closure.kind(), closure.reason());
        audit.record("APPOINTMENT_CLOSURE_ADDED", "AppointmentCentre", closure.centreCode(), null,
                Map.of("from", closure.from().toString(), "to", closure.to().toString(), "kind",
                        closure.kind().name()), closure.reason());
        events.record("itms.appointment.closure-added", closure.centreCode(), Map.of("centre", closure.centreCode(),
                "from", closure.from().toString(), "to", closure.to().toString()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Calendar.Closure> closures(String centreCode, LocalDate from, LocalDate to) {
        centre(centreCode);
        return repo.closures(centreCode, from, to);
    }

    @Transactional
    public Calendar.Resource saveResource(Calendar.Resource resource, Caller caller) {
        channelOf(caller);
        Calendar.Centre c = centre(resource.centreCode());
        requireRegion(caller, CALENDAR_MANAGE, c.region());
        repo.findResource(resource.code()).ifPresent(old -> {
            if (!old.centreCode().equals(resource.centreCode())) {
                throw DomainException.validation("RESOURCE_CENTRE_FIXED", "a resource stays at its centre",
                        List.of("CC-090"));
            }
        });
        repo.saveResource(resource);
        audit.record("APPOINTMENT_RESOURCE_SAVED", "AppointmentResource", resource.code(), null,
                Map.of("centre", resource.centreCode(), "type", resource.type().name(), "capacity",
                        resource.capacity(), "active", resource.active()), null);
        return resource;
    }

    @Transactional(readOnly = true)
    public List<Calendar.Resource> resources(String centreCode) {
        centre(centreCode);
        return repo.resources(centreCode);
    }

    @Transactional
    public Calendar.ServiceSettings saveSettings(Calendar.ServiceSettings settings, Caller caller) {
        channelOf(caller);
        require(caller.grants().holds(CALENDAR_MANAGE));
        repo.saveSettings(settings);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slotMinutes", settings.slotLength().toMinutes());
        m.put("maxChanges", settings.maxChanges());
        m.put("noShowLimit", settings.noShowLimit());
        m.put("penaltyDays", settings.penaltyDays());
        audit.record("APPOINTMENT_SETTINGS_SAVED", "AppointmentServiceSettings", settings.serviceCode(), null, m,
                null);
        return settings;
    }

    @Transactional(readOnly = true)
    public Calendar.ServiceSettings settings(String serviceCode) {
        return repo.findSettings(serviceCode).orElseThrow(() -> notFound("SERVICE_NOT_BOOKABLE", serviceCode));
    }

    // ---- slots

    /** Free slots of a service at a centre between two dates, inclusive, at most 31 days (CC-090, CC-092). */
    @Transactional(readOnly = true)
    public List<Calendar.Slot> slots(String centreCode, String serviceCode, LocalDate from, LocalDate to) {
        Calendar.Centre centre = centre(centreCode);
        Calendar.ServiceSettings settings = settings(serviceCode);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, centre.zone());
        LocalDate last = today.plusDays(settings.horizonDays());
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_SLOT_DAYS) {
            throw DomainException.validation("RANGE_INVALID", "ask for 1 to 31 days", List.of("CC-090"));
        }
        LocalDate start = from.isBefore(today) ? today : from;
        LocalDate end = to.isAfter(last) ? last : to;
        List<Calendar.Slot> slots = new ArrayList<>();
        if (!centre.active() || end.isBefore(start)) {
            return slots;
        }
        List<Calendar.Resource> resources = repo.resources(centreCode).stream()
                .filter(r -> r.active() && r.services().contains(serviceCode)).toList();
        List<Calendar.Closure> closures = repo.closures(centreCode, start, end);
        Map<String, Integer> booked = repo.bookedCounts(centreCode,
                start.atStartOfDay(centre.zone()).toInstant(), end.plusDays(1).atStartOfDay(centre.zone()).toInstant());
        ZonedDateTime notBefore = ZonedDateTime.ofInstant(now, centre.zone());
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            for (Calendar.Resource r : resources) {
                for (ZonedDateTime s : Calendar.slotStarts(centre, day, settings.slotLength(), closures, r.code(),
                        notBefore)) {
                    int used = booked.getOrDefault(AppointmentRepository.slotKey(r.code(), s.toInstant()), 0);
                    if (used < r.capacity()) {
                        slots.add(new Calendar.Slot(r.code(), s, s.plus(settings.slotLength()),
                                r.capacity() - used));
                    }
                }
            }
        }
        slots.sort(Comparator.comparing(Calendar.Slot::start).thenComparing(Calendar.Slot::resourceCode));
        return slots;
    }

    // ---- bookings (CC-091, CC-093)

    @Transactional
    public Booking book(String serviceCode, String centreCode, String resourceCode, Instant start, String customerId,
                        String transactionReference, Caller caller) {
        channelOf(caller);
        Calendar.Centre centre = centre(centreCode);
        boolean staff = !customerId.equals(caller.actor().id());
        if (staff) {
            requireRegion(caller, BOOK, centre.region());
        }
        Calendar.ServiceSettings settings = settings(serviceCode);
        Instant now = clock.instant();
        if (!staff) {
            checkPenalty(customerId, settings, now);
        }
        if (repo.activeBookingExists(customerId, serviceCode, transactionReference, now)) {
            throw DomainException.conflict("BOOKING_EXISTS", "there is already an appointment for this service",
                    List.of("CC-091"));
        }
        Calendar.Resource resource = place(centre, settings, serviceCode, resourceCode, start, now);
        Booking b = Booking.book(UUID.randomUUID(), serviceCode, centreCode, resource.code(), customerId,
                transactionReference, start, start.plus(settings.slotLength()), now);
        repo.insertBooking(b);
        audit.record("APPOINTMENT_BOOKED", "Booking", b.id().toString(), null, snapshot(b), null);
        events.record("itms.appointment.booked", b.id().toString(), snapshot(b));
        return b;
    }

    @Transactional
    public Booking change(UUID id, String resourceCode, Instant start, Caller caller) {
        channelOf(caller);
        Booking b = repo.findBookingForUpdate(id).orElseThrow(() -> notFound("BOOKING_NOT_FOUND", id.toString()));
        Calendar.Centre centre = centre(b.centreCode());
        requireOwnerOrStaff(b, caller, BOOK, centre.region());
        Calendar.ServiceSettings settings = settings(b.serviceCode());
        Instant now = clock.instant();
        Map<String, Object> before = snapshot(b);
        Calendar.Resource resource = place(centre, settings, b.serviceCode(),
                resourceCode == null ? b.resourceCode() : resourceCode, start, now);
        b.change(resource.code(), start, start.plus(settings.slotLength()), settings, now);
        repo.updateBooking(b);
        audit.record("APPOINTMENT_CHANGED", "Booking", id.toString(), before, snapshot(b), null);
        events.record("itms.appointment.changed", id.toString(), snapshot(b));
        return b;
    }

    @Transactional
    public Booking cancel(UUID id, String reason, Caller caller) {
        channelOf(caller);
        Booking b = repo.findBookingForUpdate(id).orElseThrow(() -> notFound("BOOKING_NOT_FOUND", id.toString()));
        Calendar.Centre centre = centre(b.centreCode());
        boolean own = b.customerId().equals(caller.actor().id());
        if (!own) {
            requireRegion(caller, BOOK, centre.region());
        }
        Calendar.ServiceSettings settings = settings(b.serviceCode());
        b.cancel(!own, settings.minLeadTime(), clock.instant());
        repo.updateBooking(b);
        audit.record("APPOINTMENT_CANCELLED", "Booking", id.toString(), null, snapshot(b), reason);
        events.record("itms.appointment.cancelled", id.toString(), snapshot(b));
        return b;
    }

    @Transactional
    public Booking attendance(UUID id, boolean attended, Caller caller) {
        channelOf(caller);
        Booking b = repo.findBookingForUpdate(id).orElseThrow(() -> notFound("BOOKING_NOT_FOUND", id.toString()));
        Calendar.Centre centre = centre(b.centreCode());
        requireRegion(caller, ATTENDANCE, centre.region());
        Instant now = clock.instant();
        b.attendance(attended, now);
        repo.updateBooking(b);
        audit.record(attended ? "APPOINTMENT_ATTENDED" : "APPOINTMENT_NO_SHOW", "Booking", id.toString(), null,
                snapshot(b), null);
        Map<String, Object> event = snapshot(b);
        if (!attended) {
            Calendar.ServiceSettings settings = settings(b.serviceCode());
            event.put("penaltyFeeCode", settings.penaltyFeeCode());
            int recent = repo.noShows(b.customerId(), b.serviceCode(),
                    now.minus(Duration.ofDays(settings.noShowWindowDays()))).size();
            event.put("noShowsInWindow", recent);
            event.put("bookingBlocked", settings.noShowLimit() > 0 && recent >= settings.noShowLimit());
        }
        events.record(attended ? "itms.appointment.attended" : "itms.appointment.no-show", id.toString(), event);
        return b;
    }

    @Transactional
    public Booking get(UUID id, Caller caller) {
        Booking b = repo.findBooking(id).orElseThrow(() -> notFound("BOOKING_NOT_FOUND", id.toString()));
        boolean own = b.customerId().equals(caller.actor().id());
        if (!own) {
            requireRegion(caller, READ, centre(b.centreCode()).region());
            audit.record("APPOINTMENT_VIEWED", "Booking", id.toString(), null, null, null);
        }
        return b;
    }

    @Transactional
    public List<Booking> byCustomer(String customerId, int limit, Caller caller) {
        checkLimit(limit);
        boolean own = customerId.equals(caller.actor().id());
        if (!own) {
            require(caller.grants().holds(READ));
            audit.record("APPOINTMENTS_LISTED", "Customer", customerId, null, null, null);
        }
        return repo.byCustomer(customerId, limit);
    }

    /** A centre's appointments on one day, for the centre's staff (CC-090). */
    @Transactional
    public List<Booking> byCentre(String centreCode, LocalDate day, int limit, Caller caller) {
        checkLimit(limit);
        Calendar.Centre centre = centre(centreCode);
        requireRegion(caller, READ, centre.region());
        audit.record("APPOINTMENTS_LISTED", "AppointmentCentre", centreCode, null, Map.of("day", day.toString()),
                null);
        return repo.byCentre(centreCode, day.atStartOfDay(centre.zone()).toInstant(),
                day.plusDays(1).atStartOfDay(centre.zone()).toInstant(), limit);
    }

    /** Emits reminder events for appointments starting within {@code lead} (run by the scheduled job). */
    @Transactional
    public int sendReminders(Duration lead, int limit) {
        Instant now = clock.instant();
        CurrentActor system = CurrentActor.system("appointment-reminder-job");
        List<Booking> due = repo.dueForReminder(now, now.plus(lead), limit);
        for (Booking b : due) {
            b.reminderSent(now);
            repo.updateBooking(b);
            events.recordAs(system, "itms.appointment.reminder-due", b.id().toString(), snapshot(b));
        }
        return due.size();
    }

    // ---- queue tickets (CH-031)

    /** Issues a ticket at a centre, numbered for the day (for example Q-C123-251022-7). The centre must be active. */
    @Transactional
    public QueueTicket issueTicket(String centreCode, String serviceCode, Caller caller) {
        channelOf(caller);
        Calendar.Centre centre = centre(centreCode);
        requireRegion(caller, QUEUE_MANAGE, centre.region());
        if (!centre.active()) {
            throw DomainException.conflict("CENTRE_CLOSED", "the centre does not take visitors", List.of("CH-031"));
        }
        if (serviceCode != null && repo.findSettings(serviceCode).isEmpty()) {
            throw notFound("SERVICE_NOT_BOOKABLE", serviceCode);
        }
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, centre.zone());
        repo.lockCentreForQueue(centreCode);
        int n = repo.nextTicketNo(centreCode, day.atStartOfDay(centre.zone()).toInstant(),
                day.plusDays(1).atStartOfDay(centre.zone()).toInstant());
        String no = "Q-" + centreCode + "-" + YY_MM_DD.format(day) + "-" + n;
        QueueTicket t = QueueTicket.issue(UUID.randomUUID(), centreCode, serviceCode, no,
                caller.actor().id(), now);
        repo.insertQueueTicket(t);
        audit.record("QUEUE_TICKET_ISSUED", "QueueTicket", no, null, ticketSnapshot(t), null);
        events.record("itms.appointment.queue-ticket-issued", no, Map.of("ticketNo", no, "centre", centreCode));
        return t;
    }

    /** Calls a ticket to a counter (CH-031): the waiting time ends here. */
    @Transactional
    public QueueTicket callTicket(String ticketNo, String counter, Caller caller) {
        channelOf(caller);
        QueueTicket t = repo.findQueueTicketForUpdate(ticketNo)
                .orElseThrow(() -> notFound("TICKET_NOT_FOUND", ticketNo));
        requireRegion(caller, QUEUE_MANAGE, centre(t.centreCode()).region());
        t.call(counter, clock.instant());
        repo.updateQueueTicket(t);
        audit.record("QUEUE_TICKET_CALLED", "QueueTicket", ticketNo, null, ticketSnapshot(t), null);
        events.record("itms.appointment.queue-ticket-called", ticketNo, Map.of("ticketNo", ticketNo,
                "centre", t.centreCode(), "counter", counter));
        return t;
    }

    /** Finishes the service of a ticket (CH-031): the service time ends here. */
    @Transactional
    public QueueTicket serveTicket(String ticketNo, Caller caller) {
        channelOf(caller);
        QueueTicket t = repo.findQueueTicketForUpdate(ticketNo)
                .orElseThrow(() -> notFound("TICKET_NOT_FOUND", ticketNo));
        requireRegion(caller, QUEUE_MANAGE, centre(t.centreCode()).region());
        t.serve(clock.instant());
        repo.updateQueueTicket(t);
        audit.record("QUEUE_TICKET_SERVED", "QueueTicket", ticketNo, null, ticketSnapshot(t), null);
        events.record("itms.appointment.queue-ticket-served", ticketNo, ticketSnapshot(t));
        return t;
    }

    /** The customer gives up (CH-031): a reported reason, the waiting time kept, no service time. */
    @Transactional
    public QueueTicket leaveTicket(String ticketNo, String reason, Caller caller) {
        channelOf(caller);
        QueueTicket t = repo.findQueueTicketForUpdate(ticketNo)
                .orElseThrow(() -> notFound("TICKET_NOT_FOUND", ticketNo));
        requireRegion(caller, QUEUE_MANAGE, centre(t.centreCode()).region());
        t.leave(reason, clock.instant());
        repo.updateQueueTicket(t);
        audit.record("QUEUE_TICKET_LEFT", "QueueTicket", ticketNo, null, ticketSnapshot(t), reason);
        events.record("itms.appointment.queue-ticket-left", ticketNo, Map.of("ticketNo", ticketNo,
                "centre", t.centreCode()));
        return t;
    }

    /** The queue of a day at a centre with its waiting and service times (CH-031). */
    @Transactional(readOnly = true)
    public QueueDay queueDay(String centreCode, LocalDate day, int limit, Caller caller) {
        checkLimit(limit);
        Calendar.Centre centre = centre(centreCode);
        requireRegion(caller, READ, centre.region());
        List<QueueTicket> tickets = repo.queueTickets(centreCode,
                day.atStartOfDay(centre.zone()).toInstant(), day.plusDays(1).atStartOfDay(centre.zone()).toInstant(),
                limit);
        return new QueueDay(tickets, metricsOf(tickets, limit));
    }

    static QueueMetrics metricsOf(List<QueueTicket> tickets, int limit) {
        long avgWaiting = Math.round(tickets.stream().filter(t -> t.waiting() != null)
                .mapToLong(t -> t.waiting().toMinutes()).average().orElse(0));
        long avgService = Math.round(tickets.stream().filter(t -> t.service() != null)
                .mapToLong(t -> t.service().toMinutes()).average().orElse(0));
        int called = (int) tickets.stream().filter(t -> t.waiting() != null).count();
        int served = (int) tickets.stream().filter(t -> t.service() != null).count();
        return new QueueMetrics(tickets.size(), tickets.size() >= limit, called, served, avgWaiting, avgService);
    }

    public record QueueDay(List<QueueTicket> tickets, QueueMetrics metrics) {
        public QueueDay {
            tickets = List.copyOf(tickets);
        }
    }

    public record QueueMetrics(int issued, boolean truncatedAtLimit, int called, int served, long avgWaitingMinutes,
                        long avgServiceMinutes) {
    }

    /** Checks that {@code start} is a free slot of the resource (or of any resource serving the service). */
    private Calendar.Resource place(Calendar.Centre centre, Calendar.ServiceSettings settings, String serviceCode,
                                    String resourceCode, Instant start, Instant now) {
        if (start == null) {
            throw DomainException.validation("FIELD_INVALID", "start is missing", List.of("CC-091"), "start");
        }
        if (!centre.active()) {
            throw DomainException.conflict("CENTRE_CLOSED", "the centre does not take appointments",
                    List.of("CC-092"));
        }
        ZonedDateTime local = ZonedDateTime.ofInstant(start, centre.zone());
        LocalDate day = local.toLocalDate();
        LocalDate today = LocalDate.ofInstant(now, centre.zone());
        if (day.isAfter(today.plusDays(settings.horizonDays())) || !start.isAfter(now)) {
            throw DomainException.validation("SLOT_NOT_AVAILABLE", "this time cannot be booked", List.of("CC-091"));
        }
        List<Calendar.Closure> closures = repo.closures(centre.code(), day, day);
        List<Calendar.Resource> candidates = repo.resources(centre.code()).stream()
                .filter(r -> r.active() && r.services().contains(serviceCode)
                        && (resourceCode == null || r.code().equals(resourceCode)))
                .sorted(Comparator.comparing(Calendar.Resource::code)).toList();
        for (Calendar.Resource r : candidates) {
            boolean isSlot = Calendar.slotStarts(centre, day, settings.slotLength(), closures, r.code(),
                    ZonedDateTime.ofInstant(now, centre.zone())).stream().anyMatch(s -> s.toInstant().equals(start));
            if (!isSlot) {
                continue;
            }
            repo.lockSlot(r.code(), start);
            if (repo.bookedCount(r.code(), start) < r.capacity()) {
                return r;
            }
        }
        throw DomainException.conflict("SLOT_NOT_AVAILABLE", "this time is no longer free", List.of("CC-091"));
    }

    private void checkPenalty(String customerId, Calendar.ServiceSettings s, Instant now) {
        if (s.noShowLimit() == 0 || s.penaltyDays() == 0) {
            return;
        }
        List<Booking> noShows = repo.noShows(customerId, s.serviceCode(),
                now.minus(Duration.ofDays(s.noShowWindowDays())));
        if (noShows.size() >= s.noShowLimit()) {
            Instant until = noShows.getFirst().closedAt().plus(Duration.ofDays(s.penaltyDays()));
            if (now.isBefore(until)) {
                throw DomainException.conflict("BOOKING_PENALTY",
                        "online booking is blocked after repeated no-shows", List.of("CC-093"),
                        LocalDate.ofInstant(until, clock.getZone()).toString());
            }
        }
    }

    private void requireOwnerOrStaff(Booking b, Caller caller, String permission, String region) {
        if (!b.customerId().equals(caller.actor().id())) {
            requireRegion(caller, permission, region);
        }
    }

    static Map<String, Object> ticketSnapshot(QueueTicket t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticketNo", t.ticketNo());
        m.put("centre", t.centreCode());
        m.put("serviceCode", t.serviceCode());
        m.put("status", t.status().name());
        if (t.counter() != null) {
            m.put("counter", t.counter());
        }
        if (t.waiting() != null) {
            m.put("waitingMinutes", t.waiting().toMinutes());
        }
        if (t.service() != null) {
            m.put("serviceMinutes", t.service().toMinutes());
        }
        if (t.leftReason() != null) {
            m.put("leftReason", t.leftReason());
        }
        m.put("issuedBy", t.issuedBy());
        return m;
    }

    static Map<String, Object> snapshot(Booking b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookingId", b.id().toString());
        m.put("customerId", b.customerId());
        m.put("serviceCode", b.serviceCode());
        m.put("centre", b.centreCode());
        m.put("resource", b.resourceCode());
        m.put("start", b.start().toString());
        m.put("end", b.end().toString());
        m.put("status", b.status().name());
        m.put("changes", b.changes());
        m.put("transactionReference", b.transactionReference());
        return m;
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 500) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1..500", List.of("CC-090"), "limit");
        }
    }

    private static void requireRegion(Caller caller, String permission, String region) {
        require(caller.grants().allowsInRegion(permission, region));
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with appointments", List.of("CC-001"));
        }
    }

    static void channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw DomainException.validation("CHANNEL_REQUIRED", "the request does not name its channel (X-Channel)",
                    List.of("CC-120"));
        }
    }

    private static DomainException notFound(String code, String what) {
        return new DomainException(DomainException.Kind.NOT_FOUND, code, what + " not found", List.of("CC-090"),
                what);
    }
}

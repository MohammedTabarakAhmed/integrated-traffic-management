package gov.itms.appointment.application;

import gov.itms.appointment.domain.Booking;
import gov.itms.appointment.domain.Calendar;
import gov.itms.appointment.domain.QueueTicket;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Calendars, resources, settings and bookings (CC-090..CC-093). */
public interface AppointmentRepository {

    void saveCentre(Calendar.Centre centre);

    Optional<Calendar.Centre> findCentre(String code);

    long insertClosure(Calendar.Closure closure);

    /** Closures of a centre (and its resources) that touch the date range. */
    List<Calendar.Closure> closures(String centreCode, LocalDate from, LocalDate to);

    void saveResource(Calendar.Resource resource);

    Optional<Calendar.Resource> findResource(String code);

    List<Calendar.Resource> resources(String centreCode);

    void saveSettings(Calendar.ServiceSettings settings);

    Optional<Calendar.ServiceSettings> findSettings(String serviceCode);

    /** Serialises bookings of one resource slot. */
    void lockSlot(String resourceCode, Instant start);

    /** BOOKED bookings per resource and start in the time range of a centre. */
    Map<String, Integer> bookedCounts(String centreCode, Instant from, Instant to);

    int bookedCount(String resourceCode, Instant start);

    void insertBooking(Booking booking);

    Optional<Booking> findBooking(UUID id);

    Optional<Booking> findBookingForUpdate(UUID id);

    void updateBooking(Booking booking);

    boolean activeBookingExists(String customerId, String serviceCode, String transactionReference, Instant now);

    List<Booking> byCustomer(String customerId, int limit);

    List<Booking> byCentre(String centreCode, Instant from, Instant to, int limit);

    /** NO_SHOW bookings of a customer for a service closed since {@code since}, newest first. */
    List<Booking> noShows(String customerId, String serviceCode, Instant since);

    /** BOOKED bookings starting before {@code until} with no reminder yet; locked, skipping rows others hold. */
    List<Booking> dueForReminder(Instant now, Instant until, int limit);

    // ---- queue tickets (CH-031)

    /** Serialises queue numbering of one centre for the day (lock on the centre's row). */
    void lockCentreForQueue(String centreCode);

    /** The next queue ticket number of a centre for the day (1, 2, ...). */
    int nextTicketNo(String centreCode, Instant from, Instant to);

    void insertQueueTicket(QueueTicket ticket);

    Optional<QueueTicket> findQueueTicketForUpdate(String ticketNo);

    void updateQueueTicket(QueueTicket ticket);

    /** The queue tickets issued at a centre in [{@code from}, {@code to}), oldest first. */
    List<QueueTicket> queueTickets(String centreCode, Instant from, Instant to, int limit);

    /** Key used by {@link #bookedCounts}. */
    static String slotKey(String resourceCode, Instant start) {
        return resourceCode + "@" + start.getEpochSecond();
    }
}

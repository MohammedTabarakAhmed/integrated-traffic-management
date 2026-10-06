package gov.itms.appointment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An appointment (CC-091, CC-093; ADR-052): a service at a centre on one resource from {@code start} to {@code end}.
 *
 * <p>BOOKED → (changed at most {@code maxChanges} times, only before the lead time) → CANCELLED, ATTENDED or NO_SHOW.
 * Attendance is recorded by staff after the start; closed bookings never change.
 */
public final class Booking {

    public enum Status { BOOKED, CANCELLED, ATTENDED, NO_SHOW }

    private final UUID id;
    private final String serviceCode;
    private final String centreCode;
    private final String customerId;
    private final String transactionReference;
    private final Instant createdAt;
    private String resourceCode;
    private Instant start;
    private Instant end;
    private Status status;
    private int changes;
    private Instant closedAt;
    private Instant reminderSentAt;

    private Booking(UUID id, String serviceCode, String centreCode, String customerId, String transactionReference,
                    Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.serviceCode = Objects.requireNonNull(serviceCode);
        this.centreCode = Objects.requireNonNull(centreCode);
        this.customerId = Objects.requireNonNull(customerId);
        this.transactionReference = transactionReference;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static Booking book(UUID id, String serviceCode, String centreCode, String resourceCode, String customerId,
                               String transactionReference, Instant start, Instant end, Instant now) {
        if (customerId == null || !customerId.matches("^[A-Za-z0-9._:-]{1,100}$")) {
            throw Calendar.invalid("customerId");
        }
        if (transactionReference != null && !transactionReference.matches("^[A-Z0-9][A-Z0-9-]{0,59}$")) {
            throw Calendar.invalid("transactionReference");
        }
        Booking b = new Booking(id, serviceCode, centreCode, customerId, transactionReference, now);
        b.resourceCode = resourceCode;
        b.start = start;
        b.end = end;
        b.status = Status.BOOKED;
        return b;
    }

    public static Booking restore(UUID id, String serviceCode, String centreCode, String resourceCode,
                                  String customerId, String transactionReference, Instant createdAt, Instant start,
                                  Instant end, Status status, int changes, Instant closedAt, Instant reminderSentAt) {
        Booking b = new Booking(id, serviceCode, centreCode, customerId, transactionReference, createdAt);
        b.resourceCode = resourceCode;
        b.start = start;
        b.end = end;
        b.status = status;
        b.changes = changes;
        b.closedAt = closedAt;
        b.reminderSentAt = reminderSentAt;
        return b;
    }

    /** Moves the booking (CC-091): only BOOKED, before the lead time, within the allowed number of changes. */
    public void change(String newResource, Instant newStart, Instant newEnd, Calendar.ServiceSettings settings,
                       Instant now) {
        requireBooked();
        requireBeforeLeadTime(settings.minLeadTime(), now);
        if (changes >= settings.maxChanges()) {
            throw DomainException.conflict("CHANGE_LIMIT_REACHED", "this booking was already changed "
                    + changes + " times", List.of("CC-091"), settings.maxChanges());
        }
        resourceCode = newResource;
        start = newStart;
        end = newEnd;
        changes++;
        reminderSentAt = null;
    }

    /** Cancels (CC-091). Customers only before the lead time; staff at any time before the start. */
    public void cancel(boolean staff, Duration minLeadTime, Instant now) {
        requireBooked();
        if (staff) {
            if (!now.isBefore(start)) {
                throw DomainException.conflict("BOOKING_STARTED", "the appointment has already started",
                        List.of("CC-091"));
            }
        } else {
            requireBeforeLeadTime(minLeadTime, now);
        }
        status = Status.CANCELLED;
        closedAt = now;
    }

    /** Staff record whether the customer came (CC-093), at or after the start. */
    public void attendance(boolean attended, Instant now) {
        requireBooked();
        if (now.isBefore(start)) {
            throw DomainException.conflict("BOOKING_NOT_STARTED", "attendance is recorded from the start time",
                    List.of("CC-093"));
        }
        status = attended ? Status.ATTENDED : Status.NO_SHOW;
        closedAt = now;
    }

    public void reminderSent(Instant now) {
        reminderSentAt = now;
    }

    private void requireBooked() {
        if (status != Status.BOOKED) {
            throw DomainException.conflict("BOOKING_CLOSED", "the booking is " + status, List.of("CC-091"),
                    status.name());
        }
    }

    private void requireBeforeLeadTime(Duration lead, Instant now) {
        if (now.plus(lead).isAfter(start)) {
            throw DomainException.conflict("TOO_LATE_TO_CHANGE", "changes close " + lead.toHours()
                    + " hours before the appointment", List.of("CC-091"), lead.toHours());
        }
    }

    public UUID id() {
        return id;
    }

    public String serviceCode() {
        return serviceCode;
    }

    public String centreCode() {
        return centreCode;
    }

    public String resourceCode() {
        return resourceCode;
    }

    public String customerId() {
        return customerId;
    }

    public String transactionReference() {
        return transactionReference;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant start() {
        return start;
    }

    public Instant end() {
        return end;
    }

    public Status status() {
        return status;
    }

    public int changes() {
        return changes;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public Instant reminderSentAt() {
        return reminderSentAt;
    }
}

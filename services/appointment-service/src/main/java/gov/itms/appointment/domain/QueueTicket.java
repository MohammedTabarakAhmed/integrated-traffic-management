package gov.itms.appointment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A walk-up queue ticket of a service centre (CH-031; ADR-070): issued for a service, called to a counter, then
 * served or the customer leaves. The waiting time is the ticket's own span from issue to the call, the service time
 * the span from the call to the finish; both are tracked by the ticket itself, nothing is computed elsewhere and no
 * rule is invented downstream. Closed tickets never change again; the centre's bookings (CC-091) live beside this
 * record untouched.
 */
public final class QueueTicket {

    public enum Status { ISSUED, CALLED, SERVED, LEFT }

    private final UUID id;
    private final String centreCode;
    private final String serviceCode;
    private final String ticketNo;
    private final String issuedBy;
    private final Instant issuedAt;
    private Status status;
    private String counter;
    private Instant calledAt;
    private Instant finishedAt;
    private Instant leftAt;
    private String leftReason;

    private QueueTicket(UUID id, String centreCode, String serviceCode, String ticketNo, String issuedBy,
                        Instant issuedAt) {
        this.id = Objects.requireNonNull(id);
        Objects.requireNonNull(issuedBy);
        Objects.requireNonNull(issuedAt);
        Calendar.checkCode("centre code", centreCode);
        if (serviceCode != null) {
            Calendar.checkCode("service code", serviceCode);
        }
        if (ticketNo == null || !ticketNo.matches("^[A-Z0-9][A-Z0-9_-]{1,29}$")) {
            throw Calendar.invalid("ticket number");
        }
        if (issuedBy.isBlank() || issuedBy.length() > 100) {
            throw Calendar.invalid("issuedBy");
        }
        this.centreCode = centreCode;
        this.serviceCode = serviceCode;
        this.ticketNo = ticketNo;
        this.issuedBy = issuedBy;
        this.issuedAt = issuedAt;
        this.status = Status.ISSUED;
    }

    /** A ticket issued now at {@code centreCode} for {@code serviceCode}, numbered by the caller. */
    public static QueueTicket issue(UUID id, String centreCode, String serviceCode, String ticketNo, String by,
                                    Instant now) {
        return new QueueTicket(id, centreCode, serviceCode, ticketNo, by, now);
    }

    public static QueueTicket restore(UUID id, String centreCode, String serviceCode, String ticketNo,
                                      String issuedBy, Instant issuedAt, Status status, String counter,
                                      Instant calledAt, Instant finishedAt, Instant leftAt, String leftReason) {
        QueueTicket t = new QueueTicket(id, centreCode, serviceCode, ticketNo, issuedBy, issuedAt);
        t.status = Objects.requireNonNull(status);
        if (status == Status.CALLED || status == Status.SERVED) {
            Objects.requireNonNull(calledAt, "calledAt");
            t.counter = Objects.requireNonNull(counter, "counter");
            t.calledAt = calledAt;
        }
        if (status == Status.SERVED) {
            Objects.requireNonNull(finishedAt, "finishedAt");
            t.finishedAt = finishedAt;
        }
        if (status == Status.LEFT) {
            Objects.requireNonNull(leftAt, "leftAt");
            t.leftAt = leftAt;
        }
        t.leftReason = leftReason;
        return t;
    }

    /** Calls the ticket to a counter (CH-031): the waiting time ends here. */
    public void call(String counter, Instant now) {
        require(Status.ISSUED, "TICKET_NOT_OPEN", "the ticket is " + status);
        if (counter == null || counter.isBlank() || counter.length() > 40) {
            throw Calendar.invalid("counter");
        }
        requireNotBefore(now);
        status = Status.CALLED;
        this.counter = counter;
        calledAt = now;
    }

    /** Finishes the service. The service time ends here. */
    public void serve(Instant now) {
        require(Status.CALLED, "TICKET_NOT_CALLED", "the ticket is " + status);
        requireNotBefore(now);
        status = Status.SERVED;
        finishedAt = now;
    }

    /** The customer gives up before the counter, with a reason. */
    public void leave(String reason, Instant now) {
        require(Status.ISSUED, "TICKET_NOT_OPEN", "the ticket is " + status);
        requireNotBefore(now);
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw Calendar.invalid("reason");
        }
        status = Status.LEFT;
        leftAt = now;
        leftReason = reason;
    }

    /** From issue to the call — or, for a departure that never reached a counter, to the departure; null while open. */
    public Duration waiting() {
        if (calledAt != null) {
            return Duration.between(issuedAt, calledAt);
        }
        return status == Status.LEFT && leftAt != null ? Duration.between(issuedAt, leftAt) : null;
    }

    /** From the call to the finish, or null before the finish (or after a departure). */
    public Duration service() {
        if (status != Status.SERVED) {
            return null;
        }
        return Duration.between(calledAt, finishedAt);
    }

    private void requireNotBefore(Instant now) {
        if (now.isBefore(issuedAt)) {
            throw DomainException.conflict("TICKET_TIME_BACKWARDS", "an action runs before the ticket was issued",
                    List.of("CH-031"));
        }
    }

    private void require(Status wanted, String code, String message) {
        if (status != wanted) {
            throw DomainException.conflict(code, message, List.of("CH-031"), status.name());
        }
    }

    public UUID id() {
        return id;
    }

    public String centreCode() {
        return centreCode;
    }

    public String serviceCode() {
        return serviceCode;
    }

    public String ticketNo() {
        return ticketNo;
    }

    public String issuedBy() {
        return issuedBy;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Status status() {
        return status;
    }

    public String counter() {
        return counter;
    }

    public Instant calledAt() {
        return calledAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public Instant leftAt() {
        return leftAt;
    }

    public String leftReason() {
        return leftReason;
    }
}

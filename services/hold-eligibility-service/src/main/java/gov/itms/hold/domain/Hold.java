package gov.itms.hold.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A hold on a traffic file, licence, vehicle or plate (CC-030; LLD §2.2). It records its type (CC-031), source,
 * reason, start date, optional end date, the services it blocks and the roles that may lift it. It is lifted once,
 * with a reason, or it expires after its end date; it is never changed otherwise and never deleted.
 */
public final class Hold {

    /** What the caller asks to place; validated here so every channel gets the same rules. */
    public record Spec(Subject subject, String holdType, String module, String source, String sourceRef,
                       String reason, LocalDate startDate, LocalDate endDate, List<String> blockedServices,
                       List<String> liftableByRoles) {

        public Spec {
            Objects.requireNonNull(subject, "subject");
            holdType = Codes.code("holdType", holdType);
            module = Codes.code("module", module);
            source = Codes.code("source", source);
            sourceRef = Codes.sourceRef(sourceRef);
            reason = Codes.reason(reason);
            blockedServices = Codes.blockedServices(blockedServices);
            liftableByRoles = Codes.roles(liftableByRoles);
        }
    }

    /** Placing or lifting: who, through which channel, when and (for a lift) why. */
    public record Change(Actor actor, Channel channel, Instant at, String reason) {
    }

    private final UUID id;
    private final Spec spec;
    private final LocalDate startDate;
    private final Change placed;
    private HoldStatus status;
    private Change lifted;
    private long version;

    private Hold(UUID id, Spec spec, LocalDate startDate, Change placed, HoldStatus status, Change lifted,
                 long version) {
        this.id = id;
        this.spec = spec;
        this.startDate = startDate;
        this.placed = placed;
        this.status = status;
        this.lifted = lifted;
        this.version = version;
    }

    /**
     * A new Active hold. The start date defaults to {@code today}; it may lie in the past (a court order dated
     * earlier) or the future. An end date must not be before the start date or before today (it would never apply).
     */
    public static Hold place(UUID id, Spec spec, Actor actor, Channel channel, Instant now, LocalDate today) {
        Objects.requireNonNull(channel, "channel");
        LocalDate start = spec.startDate() != null ? spec.startDate() : today;
        if (spec.endDate() != null && spec.endDate().isBefore(start)) {
            throw DomainException.validation("HOLD_DATES_INVALID", "the end date cannot be before the start date");
        }
        if (spec.endDate() != null && spec.endDate().isBefore(today)) {
            throw DomainException.validation("HOLD_END_IN_PAST", "the end date has passed, so the hold would never apply");
        }
        return new Hold(id, spec, start, new Change(actor, channel, now, null), HoldStatus.ACTIVE, null, 1);
    }

    /** Rebuilds a stored hold. */
    public static Hold restore(UUID id, Spec spec, LocalDate startDate, Change placed, HoldStatus status,
                               Change lifted, long version) {
        return new Hold(id, spec, startDate, placed, status, lifted, version);
    }

    /** Active → Lifted, once, with a reason (CC-030). An expired hold has already ended and is not lifted. */
    public void lift(Actor actor, Channel channel, String reason, Instant now, LocalDate today) {
        Objects.requireNonNull(channel, "channel");
        if (status(today) != HoldStatus.ACTIVE) {
            throw new DomainException(DomainException.Kind.CONFLICT, "HOLD_NOT_ACTIVE",
                    "this hold is " + status(today) + " and cannot be lifted", List.of("CC-030"), status(today));
        }
        lifted = new Change(actor, channel, now, Codes.reason(reason));
        status = HoldStatus.LIFTED;
        version++;
    }

    /** The status on {@code today}: a stored Active hold whose end date has passed is Expired (LLD §2.2). */
    public HoldStatus status(LocalDate today) {
        if (status == HoldStatus.ACTIVE && spec.endDate() != null && spec.endDate().isBefore(today)) {
            return HoldStatus.EXPIRED;
        }
        return status;
    }

    /** True if the hold applies on {@code today}: Active and started. */
    public boolean inForce(LocalDate today) {
        return status(today) == HoldStatus.ACTIVE && !startDate.isAfter(today);
    }

    /** True if the hold is in force and blocks {@code serviceCode} (CC-030). */
    public boolean blocks(String serviceCode, LocalDate today) {
        return inForce(today)
                && (spec.blockedServices().contains(Codes.ALL) || spec.blockedServices().contains(serviceCode));
    }

    public UUID id() {
        return id;
    }

    public Spec spec() {
        return spec;
    }

    public LocalDate startDate() {
        return startDate;
    }

    public Change placed() {
        return placed;
    }

    /** The stored status (Active or Lifted); see {@link #status(LocalDate)} for the status on a day. */
    public HoldStatus storedStatus() {
        return status;
    }

    public Change lifted() {
        return lifted;
    }

    public long version() {
        return version;
    }
}

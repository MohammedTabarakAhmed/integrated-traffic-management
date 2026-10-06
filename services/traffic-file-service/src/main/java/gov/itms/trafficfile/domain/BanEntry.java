package gov.itms.trafficfile.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An entry of the ban list (DL-013): a person barred from services, identified by an identity document (they may have
 * no traffic file yet), with a reason, a source and a period. Placed ACTIVE; lifted once with a reason; EXPIRED after
 * its end date (derived, not stored). Never changed otherwise and never deleted.
 */
public final class BanEntry {

    public enum Status { ACTIVE, LIFTED, EXPIRED }

    public static final String SOURCE_REF = "^[A-Za-z0-9._:/-]{1,100}$";
    private static final Pattern SOURCE_REF_PATTERN = Pattern.compile(SOURCE_REF);

    /** What a ban says: who, why, on whose authority, and for how long. */
    public record Spec(Identity.IdentityDocument person, String reason, String source, String sourceRef,
                       LocalDate startDate, LocalDate endDate) {
        public Spec {
            Objects.requireNonNull(person, "person");
            reason = checkedReason(reason);
            source = Codes.code("source", source);
            if (sourceRef != null && !SOURCE_REF_PATTERN.matcher(sourceRef).matches()) {
                throw DomainException.validation("FIELD_INVALID", "sourceRef is malformed", List.of("DL-013"),
                        "sourceRef");
            }
        }
    }

    /** Who did what, when, through which channel, and why. */
    public record Change(Actor actor, Channel channel, Instant at, String reason) {
        public Change {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(channel);
            Objects.requireNonNull(at);
        }
    }

    private final UUID id;
    private final Spec spec;
    private final LocalDate startDate;
    private final String fileNumber;
    private final Change placed;
    private Change lifted;
    private long version;

    private BanEntry(UUID id, Spec spec, LocalDate startDate, String fileNumber, Change placed, Change lifted,
                     long version) {
        this.id = Objects.requireNonNull(id);
        this.spec = Objects.requireNonNull(spec);
        this.startDate = Objects.requireNonNull(startDate);
        this.fileNumber = fileNumber;
        this.placed = Objects.requireNonNull(placed);
        this.lifted = lifted;
        this.version = version;
    }

    /**
     * Places a ban. The start date defaults to today; the end date, if any, is the last day it applies and must not be
     * before the start or in the past.
     *
     * @param fileNumber the person's traffic file, if they have one (shown with the file, DL-011)
     */
    public static BanEntry place(UUID id, Spec spec, String fileNumber, Actor actor, Channel channel, Instant now,
                                 LocalDate today) {
        LocalDate start = spec.startDate() == null ? today : spec.startDate();
        if (spec.endDate() != null && spec.endDate().isBefore(start)) {
            throw DomainException.validation("BAN_DATES_INVALID", "the end date is before the start date",
                    List.of("DL-013"));
        }
        if (spec.endDate() != null && spec.endDate().isBefore(today)) {
            throw DomainException.validation("BAN_END_IN_PAST", "the end date has passed", List.of("DL-013"));
        }
        return new BanEntry(id, spec, start, fileNumber, new Change(actor, channel, now, spec.reason()), null, 1);
    }

    public static BanEntry restore(UUID id, Spec spec, LocalDate startDate, String fileNumber, Change placed,
                                   Change lifted, long version) {
        return new BanEntry(id, spec, startDate, fileNumber, placed, lifted, version);
    }

    /** Lifts the ban once, with a reason. */
    public void lift(Actor actor, Channel channel, String reason, Instant now, LocalDate today) {
        if (status(today) != Status.ACTIVE) {
            throw DomainException.conflict("BAN_NOT_ACTIVE", "this ban is " + status(today), List.of("DL-013"),
                    status(today).name());
        }
        this.lifted = new Change(actor, channel, now, checkedReason(reason));
        this.version++;
    }

    /** The status on {@code today}: LIFTED once lifted, EXPIRED after the end date, otherwise ACTIVE. */
    public Status status(LocalDate today) {
        if (lifted != null) {
            return Status.LIFTED;
        }
        return spec.endDate() != null && spec.endDate().isBefore(today) ? Status.EXPIRED : Status.ACTIVE;
    }

    /** True if the ban bars the person on {@code day}: active and started. */
    public boolean inForce(LocalDate day) {
        return status(day) == Status.ACTIVE && !startDate.isAfter(day);
    }

    /** The stored status (ACTIVE or LIFTED); EXPIRED is derived. */
    public Status storedStatus() {
        return lifted == null ? Status.ACTIVE : Status.LIFTED;
    }

    static String checkedReason(String value) {
        String v = value == null ? "" : value.strip();
        if (v.length() < 3 || v.length() > 500 || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("DL-013"));
        }
        return v;
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

    public String fileNumber() {
        return fileNumber;
    }

    public Change placed() {
        return placed;
    }

    public Change lifted() {
        return lifted;
    }

    public long version() {
        return version;
    }
}

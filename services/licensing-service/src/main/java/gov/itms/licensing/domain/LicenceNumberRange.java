package gov.itms.licensing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A range of licence numbers for one region (DL-061): numbers {@code firstNumber..lastNumber}, shown as the prefix
 * followed by the number padded with zeros to {@code width} digits (format per region, Q-022).
 * <p>
 * Submitted PENDING by a maker and approved (ACTIVE) or rejected once by a different checker (CC-002). Numbers are
 * handed out in order from an active range; a range is used up when {@code nextNumber} passes {@code lastNumber}. Two
 * ranges with the same prefix never overlap, whatever their region, so a licence number is unique nationally.
 */
public final class LicenceNumberRange {

    public enum Status { PENDING, ACTIVE, REJECTED }

    private static final Pattern REGION = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final Pattern PREFIX = Pattern.compile("^[A-Z0-9]{0,5}$");
    static final int MAX_WIDTH = 12;
    static final long MAX_NUMBER = 999_999_999_999L;

    /** What a maker proposes. */
    public record Spec(String region, String prefix, int width, long firstNumber, long lastNumber) {
        public Spec {
            if (region == null || !REGION.matcher(region).matches()) {
                throw invalid("region");
            }
            prefix = prefix == null ? "" : prefix;
            if (!PREFIX.matcher(prefix).matches()) {
                throw invalid("prefix");
            }
            if (firstNumber < 1 || lastNumber > MAX_NUMBER || lastNumber < firstNumber) {
                throw DomainException.validation("NUMBER_RANGE_INVALID",
                        "the range must run from 1 or more up to at most " + MAX_NUMBER, List.of("DL-061"));
            }
            if (width < String.valueOf(lastNumber).length() || width > MAX_WIDTH) {
                throw DomainException.validation("NUMBER_WIDTH_INVALID",
                        "the width must hold the last number and be at most " + MAX_WIDTH + " digits",
                        List.of("DL-061"));
            }
        }

        public boolean overlaps(Spec other) {
            return prefix.equals(other.prefix) && firstNumber <= other.lastNumber && other.firstNumber <= lastNumber;
        }

        public String display(long number) {
            return prefix + String.format("%0" + width + "d", number);
        }
    }

    private final UUID id;
    private final Spec spec;
    private final LicenceClassVersion.Decision submitted;
    private Status status;
    private LicenceClassVersion.Decision decided;
    private long nextNumber;

    private LicenceNumberRange(UUID id, Spec spec, LicenceClassVersion.Decision submitted, Status status,
                               LicenceClassVersion.Decision decided, long nextNumber) {
        this.id = Objects.requireNonNull(id);
        this.spec = Objects.requireNonNull(spec);
        this.submitted = Objects.requireNonNull(submitted);
        this.status = Objects.requireNonNull(status);
        this.decided = decided;
        this.nextNumber = nextNumber;
    }

    public static LicenceNumberRange submit(UUID id, Spec spec, String reason, Actor maker, Channel channel,
                                            Instant now) {
        return new LicenceNumberRange(id, spec, new LicenceClassVersion.Decision(maker, channel, now,
                LicenceClassVersion.reason(reason)), Status.PENDING, null, spec.firstNumber());
    }

    public static LicenceNumberRange restore(UUID id, Spec spec, LicenceClassVersion.Decision submitted,
                                             Status status, LicenceClassVersion.Decision decided, long nextNumber) {
        return new LicenceNumberRange(id, spec, submitted, status, decided, nextNumber);
    }

    public void approve(Actor checker, Channel channel, String reason, Instant now) {
        requirePending();
        if (checker.id().equals(submitted.actor().id())) {
            throw new DomainException(DomainException.Kind.SEGREGATION_OF_DUTIES, "SAME_MAKER_CHECKER",
                    "the person who submitted a licence number range cannot approve it", List.of("DL-061", "CC-002"));
        }
        decided = new LicenceClassVersion.Decision(checker, channel, now,
                reason == null ? null : LicenceClassVersion.reason(reason));
        status = Status.ACTIVE;
    }

    public void reject(Actor checker, Channel channel, String reason, Instant now) {
        requirePending();
        decided = new LicenceClassVersion.Decision(checker, channel, now, LicenceClassVersion.reason(reason));
        status = Status.REJECTED;
    }

    /** Hands out the next number of an active range that is not used up. */
    public long take() {
        if (!hasNumbersLeft()) {
            throw new IllegalStateException("range " + id + " has no numbers left");
        }
        return nextNumber++;
    }

    public boolean hasNumbersLeft() {
        return status == Status.ACTIVE && nextNumber <= spec.lastNumber();
    }

    public long total() {
        return spec.lastNumber() - spec.firstNumber() + 1;
    }

    public long used() {
        return nextNumber - spec.firstNumber();
    }

    /** Numbers that can still be handed out: zero unless the range is active. */
    public long available() {
        return status == Status.ACTIVE ? spec.lastNumber() - nextNumber + 1 : 0;
    }

    private void requirePending() {
        if (status != Status.PENDING) {
            throw DomainException.conflict("NUMBER_RANGE_DECIDED", "this licence number range is already " + status,
                    List.of("DL-061"), status.name());
        }
    }

    private static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("DL-061"),
                field);
    }

    public UUID id() {
        return id;
    }

    public Spec spec() {
        return spec;
    }

    public LicenceClassVersion.Decision submitted() {
        return submitted;
    }

    public Status status() {
        return status;
    }

    public LicenceClassVersion.Decision decided() {
        return decided;
    }

    public long nextNumber() {
        return nextNumber;
    }
}

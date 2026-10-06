package gov.itms.vehicle.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Plate number series and plates (VL-030, VL-031, VL-032; ADR-055). */
public final class Plates {

    private Plates() {
    }

    /**
     * A number series (VL-030): per region, plate category, colour code and design, a code prefix and a number range.
     * Standard numbers are handed out in order; numbers matching the premium pattern are held back for auction
     * (VL-034).
     */
    public record Series(String code, String region, String plateCategory, String colourCode, String design,
                         String prefix, long firstNumber, long lastNumber, String premiumPattern, boolean active) {
        public Series {
            Vehicle.code("series code", code);
            Vehicle.code("region", region);
            Vehicle.code("plateCategory", plateCategory);
            Vehicle.code("colourCode", colourCode);
            Vehicle.code("design", design);
            if (prefix == null || !prefix.matches("^[A-Z0-9]{0,5}$")) {
                throw Vehicle.invalid("prefix");
            }
            if (firstNumber < 1 || lastNumber < firstNumber || lastNumber > 99_999_999L) {
                throw DomainException.validation("SERIES_RANGE_INVALID", "the number range is not valid",
                        List.of("VL-030"));
            }
            if (premiumPattern != null) {
                try {
                    Pattern.compile(premiumPattern);
                } catch (RuntimeException e) {
                    throw Vehicle.invalid("premiumPattern");
                }
                if (premiumPattern.length() > 200) {
                    throw Vehicle.invalid("premiumPattern");
                }
            }
        }

        public boolean premium(long number) {
            return premiumPattern != null && Pattern.matches(premiumPattern, Long.toString(number));
        }

        public String display(long number) {
            return (prefix.isEmpty() ? "" : prefix + " ") + number;
        }
    }

    /** Plate statuses (VL-031). */
    public enum Status { AVAILABLE, RESERVED, ASSIGNED, RETAINED, RETURNED, CANCELLED, WITHDRAWN, LOST }

    /** A plate number in use or held. Numbers never used have no row: they are AVAILABLE by definition. */
    public record Plate(UUID id, String seriesCode, long number, String display, Status status, UUID vehicleId,
                        String holderFileNumber, Instant changedAt) {
        public Plate {
            Objects.requireNonNull(id);
            Objects.requireNonNull(seriesCode);
            Objects.requireNonNull(status);
        }

        /** VL-031 transitions allowed from this plate's status. */
        public Plate moveTo(Status next, UUID vehicle, String holder, Instant now) {
            boolean ok = switch (status) {
                case AVAILABLE -> next == Status.RESERVED || next == Status.ASSIGNED || next == Status.WITHDRAWN;
                case RESERVED -> next == Status.ASSIGNED || next == Status.AVAILABLE || next == Status.CANCELLED;
                case ASSIGNED -> next == Status.RETAINED || next == Status.RETURNED || next == Status.LOST
                        || next == Status.CANCELLED;
                case RETAINED -> next == Status.ASSIGNED || next == Status.RETURNED || next == Status.CANCELLED;
                case WITHDRAWN -> next == Status.ASSIGNED || next == Status.RETAINED;
                case RETURNED, CANCELLED, LOST -> false;
            };
            if (!ok) {
                throw DomainException.conflict("PLATE_STATUS_INVALID", "a plate cannot go from " + status + " to "
                        + next, List.of("VL-031"), status.name(), next.name());
            }
            return new Plate(id, seriesCode, number, display, next, vehicle, holder, now);
        }
    }
}

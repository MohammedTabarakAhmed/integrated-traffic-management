package gov.itms.violation.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A recorded violation (VF-014, VF-020, VF-021, VF-070; ADR-056): which catalogue version, when and where, which
 * vehicle, who is responsible, the fine and black points fixed when recorded, the evidence and its payable at
 * payment-service.
 *
 * <p>OPEN → PAID (payment confirmed) → OPEN again if the receipt is cancelled; OPEN → CANCELLED by a supervisor with a
 * reason code. Paid and cancelled violations never change otherwise.
 */
public final class Violation {

    public enum Status { OPEN, PAID, CANCELLED }

    /** PARTNER: loaded from a partner state's exchange message (XB-002). */
    public enum Source { DEVICE, OFFICER, PARTNER }

    private static final Pattern REF = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,59}$");

    /** What the officer or device reports. */
    public record Report(String catalogueCode, Instant occurredAt, String region, String location, String plate,
                         String vin, String ownerFileNumber, String driverFileNumber, Integer speedLimitKmh,
                         Integer measuredSpeedKmh, Source source, String deviceId, List<String> evidenceIds,
                         String renterFileNumber, UUID rentalContractId) {

        /** A report as given by the caller; the renter is found by the service from rental contracts (TR-032). */
        @SuppressWarnings("checkstyle:ParameterNumber")
        public Report(String catalogueCode, Instant occurredAt, String region, String location, String plate,
                      String vin, String ownerFileNumber, String driverFileNumber, Integer speedLimitKmh,
                      Integer measuredSpeedKmh, Source source, String deviceId, List<String> evidenceIds) {
            this(catalogueCode, occurredAt, region, location, plate, vin, ownerFileNumber, driverFileNumber,
                    speedLimitKmh, measuredSpeedKmh, source, deviceId, evidenceIds, null, null);
        }

        public Report {
            CatalogueEntry.code("catalogueCode", catalogueCode);
            Objects.requireNonNull(occurredAt, "occurredAt");
            CatalogueEntry.code("region", region);
            if (location == null || location.isBlank() || location.length() > 300) {
                throw CatalogueEntry.invalid("location");
            }
            if (plate != null && !plate.matches("^[A-Z0-9 -]{1,20}$")) {
                throw CatalogueEntry.invalid("plate");
            }
            if (vin != null && !vin.matches("^[A-HJ-NPR-Z0-9]{17}$")) {
                throw CatalogueEntry.invalid("vin");
            }
            if (plate == null && vin == null && driverFileNumber == null) {
                throw DomainException.validation("SUBJECT_REQUIRED", "give the vehicle or the driver",
                        List.of("VF-014"));
            }
            ref("ownerFileNumber", ownerFileNumber, false);
            ref("driverFileNumber", driverFileNumber, false);
            if (ownerFileNumber == null && driverFileNumber == null) {
                throw DomainException.validation("RESPONSIBLE_UNKNOWN", "the owner or the driver must be known",
                        List.of("VF-014"));
            }
            if ((speedLimitKmh == null) != (measuredSpeedKmh == null)
                    || (speedLimitKmh != null && (speedLimitKmh < 5 || speedLimitKmh > 200 || measuredSpeedKmh < 0
                    || measuredSpeedKmh > 500))) {
                throw CatalogueEntry.invalid("speed");
            }
            Objects.requireNonNull(source, "source");
            if (source == Source.DEVICE && (deviceId == null || !deviceId.matches("^[A-Z0-9][A-Z0-9_-]{0,39}$"))) {
                throw CatalogueEntry.invalid("deviceId");
            }
            ref("renterFileNumber", renterFileNumber, false);
            if (renterFileNumber != null && (driverFileNumber != null || rentalContractId == null)) {
                throw CatalogueEntry.invalid("renterFileNumber");
            }
            evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
            if (evidenceIds.size() > 20 || evidenceIds.stream().anyMatch(e -> !e.matches("^[A-Za-z0-9-]{1,64}$"))) {
                throw CatalogueEntry.invalid("evidenceIds");
            }
        }

        /** VF-014: the identified driver; otherwise the renter whose contract covers the time; otherwise the owner. */
        public String responsibleFileNumber() {
            if (driverFileNumber != null) {
                return driverFileNumber;
            }
            return renterFileNumber != null ? renterFileNumber : ownerFileNumber;
        }

        /**
         * The report with the rental contract that covered the time (TR-032). {@code renter} is null for a visitor
         * without a traffic file: the contract is kept for the record and the owner stays responsible (Q-081).
         */
        public Report coveredBy(UUID contractId, String renter) {
            return new Report(catalogueCode, occurredAt, region, location, plate, vin, ownerFileNumber,
                    driverFileNumber, speedLimitKmh, measuredSpeedKmh, source, deviceId, evidenceIds, renter,
                    Objects.requireNonNull(contractId));
        }

        public Integer overKmh() {
            return speedLimitKmh == null ? null : Math.max(0, measuredSpeedKmh - speedLimitKmh);
        }
    }

    private final UUID id;
    private final String number;
    private final Report report;
    private final String catalogueCode;
    private final int catalogueVersion;
    private final long fineMinor;
    private final int blackPoints;
    private final boolean repeat;
    private final String recordedBy;
    private final Instant recordedAt;
    private Status status;
    private UUID payableId;
    private String cancelReason;
    private Instant closedAt;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public Violation(UUID id, String number, Report report, String catalogueCode, int catalogueVersion,
                     long fineMinor, int blackPoints, boolean repeat, String recordedBy, Instant recordedAt,
                     Status status, UUID payableId, String cancelReason, Instant closedAt) {
        this.id = Objects.requireNonNull(id);
        this.number = Objects.requireNonNull(number);
        this.report = Objects.requireNonNull(report);
        this.catalogueCode = catalogueCode;
        this.catalogueVersion = catalogueVersion;
        this.fineMinor = fineMinor;
        this.blackPoints = blackPoints;
        this.repeat = repeat;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
        this.status = Objects.requireNonNull(status);
        this.payableId = payableId;
        this.cancelReason = cancelReason;
        this.closedAt = closedAt;
    }

    public void payableRegistered(UUID payable) {
        this.payableId = payable;
    }

    public void paid(Instant now) {
        requireOpen();
        status = Status.PAID;
        closedAt = now;
    }

    /** Back to OPEN when the payment's receipt is cancelled (FN-011). */
    public void reopened() {
        if (status != Status.PAID) {
            throw new IllegalStateException("only a paid violation reopens");
        }
        status = Status.OPEN;
        closedAt = null;
    }

    /** VF-021: a supervisor cancels with a reason code from the configured list. */
    public void cancel(String reasonCode, Instant now) {
        requireOpen();
        status = Status.CANCELLED;
        cancelReason = reasonCode;
        closedAt = now;
    }

    private void requireOpen() {
        if (status != Status.OPEN) {
            throw DomainException.conflict("VIOLATION_CLOSED", "the violation is " + status, List.of("VF-020"),
                    status.name());
        }
    }

    public LocalDate occurredOn(java.time.ZoneId zone) {
        return LocalDate.ofInstant(report.occurredAt(), zone);
    }

    static void ref(String field, String value, boolean required) {
        if (value == null ? required : !REF.matcher(value).matches()) {
            throw CatalogueEntry.invalid(field);
        }
    }

    public UUID id() {
        return id;
    }

    public String number() {
        return number;
    }

    public Report report() {
        return report;
    }

    public String catalogueCode() {
        return catalogueCode;
    }

    public int catalogueVersion() {
        return catalogueVersion;
    }

    public long fineMinor() {
        return fineMinor;
    }

    public int blackPoints() {
        return blackPoints;
    }

    public boolean repeat() {
        return repeat;
    }

    public String recordedBy() {
        return recordedBy;
    }

    public Instant recordedAt() {
        return recordedAt;
    }

    public Status status() {
        return status;
    }

    public UUID payableId() {
        return payableId;
    }

    public String cancelReason() {
        return cancelReason;
    }

    public Instant closedAt() {
        return closedAt;
    }
}

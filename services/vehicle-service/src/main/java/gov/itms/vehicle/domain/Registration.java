package gov.itms.vehicle.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Registration requests and inspection results (VL-010, VL-011, VL-040; ADR-055). */
public final class Registration {

    private Registration() {
    }

    public enum Kind { FIRST, RENEWAL }

    public enum Status { AWAITING_PAYMENT, COMPLETED, CANCELLED }

    /**
     * A first registration or renewal that passed every check and waits for its fee (the payable at payment-service).
     * Completed when payment-service confirms the payment; the new expiry and plate were fixed when it was made.
     */
    public record Request(UUID id, UUID vehicleId, Kind kind, Status status, String quoteId, UUID payableId,
                          long amountMinor, String currency, LocalDate newExpiry, UUID plateId, String requestedBy,
                          String channel, Instant createdAt, Instant completedAt) {
        public Request {
            Objects.requireNonNull(id);
            Objects.requireNonNull(vehicleId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(status);
            Objects.requireNonNull(newExpiry);
        }

        public Request completed(Instant now) {
            if (status != Status.AWAITING_PAYMENT) {
                throw DomainException.conflict("REGISTRATION_NOT_AWAITING", "the request is " + status,
                        List.of("VL-010"), status.name());
            }
            return new Request(id, vehicleId, kind, Status.COMPLETED, quoteId, payableId, amountMinor, currency,
                    newExpiry, plateId, requestedBy, channel, createdAt, now);
        }

        public Request cancelled(Instant now) {
            if (status != Status.AWAITING_PAYMENT) {
                throw DomainException.conflict("REGISTRATION_NOT_AWAITING", "the request is " + status,
                        List.of("VL-010"), status.name());
            }
            return new Request(id, vehicleId, kind, Status.CANCELLED, quoteId, payableId, amountMinor, currency,
                    newExpiry, plateId, requestedBy, channel, createdAt, now);
        }
    }

    /**
     * An inspection result from an inspection centre (VL-025, VL-040): centre, date, odometer, results by test area,
     * pass or fail, photos (document ids).
     */
    public record Inspection(UUID id, UUID vehicleId, String centreCode, LocalDate inspectedOn, long odometerKm,
                             Map<String, String> results, boolean passed, List<String> photoDocumentIds,
                             String recordedBy, Instant recordedAt) {
        public Inspection {
            Objects.requireNonNull(id);
            Vehicle.code("centreCode", centreCode);
            Objects.requireNonNull(inspectedOn);
            if (odometerKm < 0 || odometerKm > 10_000_000) {
                throw Vehicle.invalid("odometerKm");
            }
            results = Map.copyOf(results == null ? Map.of() : results);
            if (results.size() > 50) {
                throw Vehicle.invalid("results");
            }
            results.forEach((area, outcome) -> {
                Vehicle.code("test area", area);
                if (!"PASS".equals(outcome) && !"FAIL".equals(outcome) && !"ADVISORY".equals(outcome)) {
                    throw Vehicle.invalid("result of " + area);
                }
            });
            photoDocumentIds = List.copyOf(photoDocumentIds == null ? List.of() : photoDocumentIds);
            if (photoDocumentIds.size() > 30) {
                throw Vehicle.invalid("photoDocumentIds");
            }
        }
    }
}

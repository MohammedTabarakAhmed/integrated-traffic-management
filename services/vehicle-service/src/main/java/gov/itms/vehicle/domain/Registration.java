package gov.itms.vehicle.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Paid vehicle and plate transactions, and inspection results (VL-010…VL-039, VL-040; ADR-055, ADR-059). */
public final class Registration {

    private Registration() {
    }

    /**
     * What a paid transaction does once its fee is paid: first registration (VL-010), renewal (VL-011), transfer of
     * ownership (VL-012), change of details (VL-013), card replacement (VL-014), de-registration (VL-015), a
     * certificate (VL-020), a temporary entry/exit permit (VL-016), a plate number reservation (VL-033), retention
     * renewal (VL-039), plate sale (VL-035), plate exchange onto a vehicle (VL-035), plate replacement after loss
     * (VL-037), a trial plate (VL-038) or an auction purchase (VL-034).
     */
    public enum Kind {
        FIRST, RENEWAL, TRANSFER, CHANGE, CARD, DEREGISTER, CERTIFICATE, TEMP_PERMIT, PLATE_RESERVATION,
        PLATE_RETENTION, PLATE_SALE, PLATE_EXCHANGE, PLATE_REPLACEMENT, TRIAL_PLATE, AUCTION
    }

    /** AWAITING_CONSENT only for a transfer waiting for the buyer (VL-012). */
    public enum Status { AWAITING_CONSENT, AWAITING_PAYMENT, COMPLETED, CANCELLED }

    /**
     * A transaction that passed every check and waits for its fee (the payable at payment-service). Completed when
     * payment-service confirms the payment; what it changes was fixed when it was made ({@code newExpiry},
     * {@code plateId}, {@code payload}). {@code vehicleId} is empty for plate-only transactions; {@code subjectRef}
     * names their subject (plate, permit, auction lot...); {@code payerFile} is the traffic file that pays.
     */
    public record Request(UUID id, UUID vehicleId, Kind kind, Status status, String quoteId, UUID payableId,
                          long amountMinor, String currency, LocalDate newExpiry, UUID plateId, String requestedBy,
                          String channel, Instant createdAt, Instant completedAt, Map<String, String> payload,
                          String subjectRef, String payerFile) {
        public Request {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(status);
            if ((kind == Kind.FIRST || kind == Kind.RENEWAL) && (vehicleId == null || newExpiry == null)) {
                throw new IllegalArgumentException("a registration names its vehicle and new expiry");
            }
            payload = payload == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }

        @SuppressWarnings("checkstyle:ParameterNumber")
        public Request(UUID id, UUID vehicleId, Kind kind, Status status, String quoteId, UUID payableId,
                       long amountMinor, String currency, LocalDate newExpiry, UUID plateId, String requestedBy,
                       String channel, Instant createdAt, Instant completedAt) {
            this(id, vehicleId, kind, status, quoteId, payableId, amountMinor, currency, newExpiry, plateId,
                    requestedBy, channel, createdAt, completedAt, Map.of(), null, null);
        }

        public String value(String key) {
            return payload.get(key);
        }

        public Request completed(Instant now) {
            return moved(Status.COMPLETED, now);
        }

        public Request cancelled(Instant now) {
            if (status == Status.AWAITING_CONSENT) {
                return new Request(id, vehicleId, kind, Status.CANCELLED, quoteId, payableId, amountMinor, currency,
                        newExpiry, plateId, requestedBy, channel, createdAt, now, payload, subjectRef, payerFile);
            }
            return moved(Status.CANCELLED, now);
        }

        /** The buyer consented and the fee was asked (VL-012). */
        public Request priced(String quote, UUID payable, long amount, String cur, UUID plate,
                              Map<String, String> newPayload) {
            if (status != Status.AWAITING_CONSENT) {
                throw DomainException.conflict("TRANSFER_NOT_AWAITING_CONSENT", "the transfer is " + status,
                        List.of("VL-012"), status.name());
            }
            return new Request(id, vehicleId, kind, Status.AWAITING_PAYMENT, quote, Objects.requireNonNull(payable),
                    amount, cur, newExpiry, plate, requestedBy, channel, createdAt, null, newPayload, subjectRef,
                    payerFile);
        }

        private Request moved(Status next, Instant now) {
            if (status != Status.AWAITING_PAYMENT) {
                throw DomainException.conflict("REGISTRATION_NOT_AWAITING", "the request is " + status,
                        List.of("VL-010"), status.name());
            }
            return new Request(id, vehicleId, kind, next, quoteId, payableId, amountMinor, currency, newExpiry,
                    plateId, requestedBy, channel, createdAt, now, payload, subjectRef, payerFile);
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

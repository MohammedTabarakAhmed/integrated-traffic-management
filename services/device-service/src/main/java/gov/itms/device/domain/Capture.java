package gov.itms.device.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable source report and its quality-review state (VF-010..VF-012, VF-081). */
public record Capture(UUID id, String sourceKey, String deviceCode, Instant capturedAt, String catalogueCode,
                      String region, String plate, String location, int speedLimitKmh, Integer measuredSpeedKmh, String evidenceId,
                      String ownerFileNumber, Status status, List<String> qualityReasons, String violationNumber,
                      Instant receivedAt) {

    public enum Status { REVIEW, READY, RECORDED, REJECTED }

    public Capture {
        Objects.requireNonNull(id);
        Objects.requireNonNull(capturedAt);
        Objects.requireNonNull(receivedAt);
        Objects.requireNonNull(status);
        qualityReasons = qualityReasons == null ? List.of() : List.copyOf(qualityReasons);
        if (sourceKey == null || !sourceKey.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$")
                || deviceCode == null || !deviceCode.matches("^[A-Z0-9][A-Z0-9_-]{0,39}$")
                || catalogueCode == null || !catalogueCode.matches("^[A-Z0-9][A-Z0-9_-]{0,39}$")
                || region == null || !region.matches("^[A-Z0-9][A-Z0-9_-]{0,39}$")
                || plate == null || !plate.matches("^[A-Z0-9 -]{1,20}$") || location == null
                || location.isBlank() || location.length() > 300 || evidenceId == null
                || !evidenceId.matches("^[A-Za-z0-9-]{1,64}$") || speedLimitKmh < 5 || speedLimitKmh > 200
                || (measuredSpeedKmh != null && (measuredSpeedKmh < 0 || measuredSpeedKmh > 500))) {
            throw DomainException.validation("CAPTURE_INVALID", "device capture fields are invalid", "VF-010");
        }
        if (ownerFileNumber != null && !ownerFileNumber.matches("^[A-Za-z0-9][A-Za-z0-9_./:-]{0,59}$")) {
            throw DomainException.validation("OWNER_FILE_INVALID", "owner file reference is invalid", "VF-014");
        }
    }

    public static Capture quality(UUID id, String sourceKey, Device device, Instant capturedAt, String catalogueCode,
                                  String region, String plate, String location, Integer measuredSpeedKmh,
                                  String evidenceId, String ownerFileNumber, boolean plateReadable,
                                  boolean imagePresent, Instant receivedAt) {
        java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
        if (!plateReadable) {
            reasons.add("PLATE_UNREADABLE");
        }
        if (!imagePresent) {
            reasons.add("IMAGE_MISSING");
        }
        if (ownerFileNumber == null) {
            reasons.add("RESPONSIBLE_PARTY_UNRESOLVED");
        }
        if (!device.calibratedAt(capturedAt)) {
            reasons.add("CALIBRATION_INVALID");
        }
        return new Capture(id, sourceKey, device.code(), capturedAt, catalogueCode, region, plate, location,
                device.speedLimitKmh(),
                measuredSpeedKmh, evidenceId, ownerFileNumber, reasons.isEmpty() ? Status.READY : Status.REVIEW,
                reasons, null, receivedAt);
    }

    public Capture reviewed(boolean accept, String violationNumber) {
        if (status != Status.REVIEW && status != Status.READY) {
            throw DomainException.conflict("CAPTURE_CLOSED", "capture is already " + status, "VF-011");
        }
        if (accept && ownerFileNumber == null) {
            throw DomainException.validation("RESPONSIBLE_PARTY_REQUIRED", "resolve the vehicle owner before recording",
                    "VF-014");
        }
        return new Capture(id, sourceKey, deviceCode, capturedAt, catalogueCode, region, plate, location,
                speedLimitKmh,
                measuredSpeedKmh, evidenceId, ownerFileNumber, accept ? Status.RECORDED : Status.REJECTED,
                qualityReasons, accept ? violationNumber : null, receivedAt);
    }
}

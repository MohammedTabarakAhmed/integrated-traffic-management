package gov.itms.device.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A registered traffic enforcement device and its calibration state (VF-080, VF-081). */
public record Device(UUID id, String code, Type type, String owner, String region, double latitude, double longitude, String road,
                     String direction, int speedLimitKmh, Status status, Instant calibratedAt,
                     Instant calibrationExpiresAt, Instant createdAt, String createdBy) {

    public enum Type { RADAR, RED_LIGHT_CAMERA, MOBILE_CAMERA }

    public enum Status { ACTIVE, SUSPENDED, RETIRED }

    public Device {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(status);
        if (code == null || !code.matches("^[A-Z0-9][A-Z0-9_-]{0,39}$") || owner == null || owner.isBlank()
                || region == null || !region.matches("^[A-Z0-9][A-Z0-9_-]{0,39}$")
                || owner.length() > 100 || !Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180
                || road == null || road.isBlank() || road.length() > 120 || direction == null
                || direction.isBlank() || direction.length() > 40 || speedLimitKmh < 5 || speedLimitKmh > 200) {
            throw DomainException.validation("DEVICE_INVALID", "device registration details are invalid",
                    "VF-080", "RE-003");
        }
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(createdBy);
    }

    public boolean calibratedAt(Instant captureAt) {
        return status == Status.ACTIVE && calibratedAt != null && calibrationExpiresAt != null
                && !captureAt.isBefore(calibratedAt) && captureAt.isBefore(calibrationExpiresAt);
    }

    public Device calibrated(Instant from, Instant until) {
        if (status != Status.ACTIVE || from == null || until == null || !until.isAfter(from)) {
            throw DomainException.validation("CALIBRATION_INVALID", "active device needs a valid calibration period",
                    "VF-080", "VF-081");
        }
        return new Device(id, code, type, owner, region, latitude, longitude, road, direction, speedLimitKmh, status,
                from, until, createdAt, createdBy);
    }
}

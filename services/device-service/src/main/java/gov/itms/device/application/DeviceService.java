package gov.itms.device.application;

import gov.itms.device.domain.Capture;
import gov.itms.device.domain.Device;
import gov.itms.device.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Device registry, calibration and quality-reviewed intake (VF-010..VF-013, VF-080..VF-082). */
public class DeviceService {

    public static final String MANAGE = "device.manage";
    public static final String INGEST = "device.capture.ingest";
    public static final String REVIEW = "device.capture.review";
    public static final String READ = "device.read";

    private final Ports.DeviceRepository repo;
    private final Ports.ViolationRecorder violations;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;
    private final Duration maximumLag;

    public DeviceService(Ports.DeviceRepository repo, Ports.ViolationRecorder violations, AuditRecorder audit,
                         DomainEventRecorder events, Clock clock, Duration maximumLag) {
        this.repo = repo;
        this.violations = violations;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.maximumLag = maximumLag;
        if (maximumLag.isNegative() || maximumLag.isZero() || maximumLag.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("itms.devices.maximum-lag must be between 1 second and 5 minutes");
        }
    }

    @Transactional
    public Device register(String code, Device.Type type, String owner, String region, double latitude, double longitude,
                           String road, String direction, int speedLimitKmh, Caller caller) {
        require(caller, MANAGE, region);
        Device d = new Device(UUID.randomUUID(), code, type, owner, region, latitude, longitude, road, direction,
                speedLimitKmh, Device.Status.ACTIVE, null, null, clock.instant(), caller.id());
        if (repo.device(code).isPresent()) {
            throw DomainException.conflict("DEVICE_EXISTS", "device " + code + " already exists", "VF-080");
        }
        repo.insert(d);
        audit.record("DEVICE_REGISTERED", "Device", code, null, Map.of("type", type.name(), "road", road), null);
        return d;
    }

    @Transactional
    public Device calibrate(String code, Instant validFrom, Instant validUntil, Caller caller) {
        Device current = repo.deviceForUpdate(code).orElseThrow(() -> missing(code));
        require(caller, MANAGE, current.region());
        Device updated = current.calibrated(validFrom, validUntil);
        repo.updateCalibration(updated, caller.id(), clock.instant());
        audit.record("DEVICE_CALIBRATED", "Device", code,
                current.calibratedAt() == null ? null : Map.of("calibrationExpiresAt",
                        current.calibrationExpiresAt().toString()),
                Map.of("calibratedAt", validFrom.toString(), "calibrationExpiresAt", validUntil.toString()), null);
        events.record("itms.device.calibrated", code, Map.of("deviceId", code,
                "calibratedAt", validFrom.toString(), "calibrationExpiresAt", validUntil.toString()));
        return updated;
    }

    @Transactional(readOnly = true)
    public List<Device> devices(String region, Caller caller) {
        if (!caller.grants().allows(READ, region)) {
            throw new DomainException(DomainException.Kind.FORBIDDEN, "DEVICE_FORBIDDEN",
                    "permission required: " + READ, List.of("VF-080"));
        }
        return repo.devices(region);
    }

    /** Device capture is deduplicated on the source key and screened before it can be recorded (VF-010, VF-011). */
    @Transactional
    public Capture ingest(String sourceKey, String deviceCode, Instant capturedAt, String catalogueCode, String region,
                         String plate, String location, Integer measuredSpeedKmh, String evidenceId,
                         String ownerFileNumber, boolean plateReadable, boolean imagePresent, Caller caller) {
        require(caller, INGEST, region);
        Instant received = clock.instant();
        if (capturedAt == null || capturedAt.isAfter(received.plusSeconds(60))
                || received.isAfter(capturedAt.plus(maximumLag))) {
            throw DomainException.validation("CAPTURE_DELAY_EXCEEDED", "device capture is older than the permitted lag",
                    "VF-010");
        }
        Device device = repo.device(deviceCode).orElseThrow(() -> missing(deviceCode));
        if (!device.region().equals(region)) {
            throw DomainException.validation("CAPTURE_REGION_MISMATCH",
                    "capture region must match the registered device region", "VF-010", "VF-080");
        }
        Capture capture = Capture.quality(UUID.randomUUID(), sourceKey, device, capturedAt, catalogueCode, region,
                plate, location, measuredSpeedKmh, evidenceId, ownerFileNumber, plateReadable, imagePresent, received);
        if (!repo.insertCapture(capture)) {
            return repo.captureBySource(sourceKey).orElseThrow(() -> DomainException.conflict("CAPTURE_DUPLICATE",
                    "source key was already received", "VF-010", "VF-012"));
        }
        if (capture.status() == Capture.Status.READY) {
            String number = violations.record(capture, caller.id());
            capture = capture.reviewed(true, number);
            repo.updateCapture(capture);
        }
        String action = switch (capture.status()) {
            case REVIEW -> "DEVICE_CAPTURE_QUEUED";
            case RECORDED -> "DEVICE_CAPTURE_RECORDED";
            case READY -> "DEVICE_CAPTURE_READY";
            case REJECTED -> "DEVICE_CAPTURE_REJECTED";
        };
        audit.record(action,
                "DeviceCapture", capture.id().toString(), null,
                Map.of("deviceId", deviceCode, "qualityReasons", capture.qualityReasons()), null);
        events.record("itms.device.capture-received", capture.id().toString(), Map.of("captureId",
                capture.id().toString(), "deviceId", deviceCode, "status", capture.status().name(),
                "receivedAt", received.toString()));
        return capture;
    }

    @Transactional(readOnly = true)
    public List<Capture> reviewQueue(String region, int limit, Caller caller) {
        require(caller, REVIEW, region);
        if (limit < 1 || limit > 1000) {
            throw DomainException.validation("PAGE_INVALID", "limit must be 1..1000", "VF-011");
        }
        return repo.captures(Capture.Status.REVIEW, region, limit);
    }

    /** An officer resolves quality exceptions and submits approved captures once to violation-service. */
    @Transactional
    public Capture review(UUID id, boolean accept, String ownerFileNumber, Caller caller) {
        Capture current = repo.captureForUpdate(id).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "CAPTURE_NOT_FOUND", "capture " + id + " does not exist",
                List.of("VF-011")));
        require(caller, REVIEW, current.region());
        if (ownerFileNumber != null) {
            current = new Capture(current.id(), current.sourceKey(), current.deviceCode(), current.capturedAt(),
                    current.catalogueCode(), current.region(), current.plate(), current.location(), current.speedLimitKmh(),
                    current.measuredSpeedKmh(), current.evidenceId(), ownerFileNumber, current.status(),
                    current.qualityReasons(), current.violationNumber(), current.receivedAt());
        }
        String violationNumber = accept ? violations.record(current, caller.id()) : null;
        Capture result = current.reviewed(accept, violationNumber);
        repo.updateCapture(result);
        audit.record(accept ? "DEVICE_CAPTURE_ACCEPTED" : "DEVICE_CAPTURE_REJECTED", "DeviceCapture", id.toString(),
                Map.of("status", current.status().name()), Map.of("status", result.status().name()), caller.id());
        events.record(accept ? "itms.device.capture-recorded" : "itms.device.capture-rejected", id.toString(),
                Map.of("captureId", id.toString(), "violationNumber", violationNumber == null ? "" : violationNumber));
        return result;
    }

    private static void require(Caller caller, String permission, String region) {
        boolean allowed = region == null ? caller.grants().holds(permission) : caller.grants().allows(permission, region);
        if (!allowed) {
            throw new DomainException(DomainException.Kind.FORBIDDEN, "DEVICE_FORBIDDEN",
                    "permission required: " + permission, List.of("VF-080", "VF-010"));
        }
    }

    private static DomainException missing(String code) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "DEVICE_NOT_FOUND", "device " + code
                + " does not exist", List.of("VF-080"));
    }
}

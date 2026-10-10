package gov.itms.device.api;

import gov.itms.device.application.DeviceService;
import gov.itms.device.domain.Capture;
import gov.itms.device.domain.Device;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Device registry, calibration and capture intake API. */
@RestController
class DeviceController {

    private final DeviceService devices;
    private final Callers callers;

    DeviceController(DeviceService devices, Callers callers) {
        this.devices = devices;
        this.callers = callers;
    }

    record DeviceRequest(@NotBlank String code, @NotNull Device.Type type, @NotBlank String owner,
                         @NotBlank String region, double latitude, double longitude, @NotBlank String road,
                         @NotBlank String direction, @Positive int speedLimitKmh) { }

    record CalibrationRequest(@NotNull Instant validFrom, @NotNull Instant validUntil) { }

    record CaptureRequest(@NotBlank @Size(max = 120) String sourceKey, @NotBlank String deviceCode,
                          @NotNull Instant capturedAt, @NotBlank String catalogueCode, @NotBlank String region,
                          @NotBlank String plate, @NotBlank @Size(max = 300) String location,
                          Integer measuredSpeedKmh, @NotBlank String evidenceId, String ownerFileNumber,
                          boolean plateReadable, boolean imagePresent) { }

    record ReviewRequest(@NotNull Boolean accept, String ownerFileNumber) { }

    record Collection<T>(List<T> items) { }

    @PostMapping("/v1/devices")
    @PreAuthorize("hasAuthority('device.manage')")
    ResponseEntity<Device> register(@Valid @RequestBody DeviceRequest body) {
        Device device = devices.register(body.code(), body.type(), body.owner(), body.region(), body.latitude(),
                body.longitude(), body.road(), body.direction(), body.speedLimitKmh(), callers.current());
        return ResponseEntity.created(URI.create("/v1/devices/" + device.code())).body(device);
    }

    @GetMapping("/v1/devices")
    @PreAuthorize("hasAuthority('device.read')")
    Collection<Device> list(@RequestParam String region) {
        return new Collection<>(devices.devices(region, callers.current()));
    }

    @PostMapping("/v1/devices/{code}/calibrations")
    @PreAuthorize("hasAuthority('device.manage')")
    Device calibrate(@PathVariable String code, @Valid @RequestBody CalibrationRequest body) {
        return devices.calibrate(code, body.validFrom(), body.validUntil(), callers.current());
    }

    @PostMapping("/v1/device-captures")
    @PreAuthorize("hasAuthority('device.capture.ingest')")
    ResponseEntity<Capture> ingest(@Valid @RequestBody CaptureRequest body) {
        Capture capture = devices.ingest(body.sourceKey(), body.deviceCode(), body.capturedAt(),
                body.catalogueCode(), body.region(), body.plate(), body.location(), body.measuredSpeedKmh(),
                body.evidenceId(), body.ownerFileNumber(), body.plateReadable(), body.imagePresent(), callers.current());
        return ResponseEntity.created(URI.create("/v1/device-captures/" + capture.id())).body(capture);
    }

    @GetMapping("/v1/device-captures/review-queue")
    @PreAuthorize("hasAuthority('device.capture.review')")
    Collection<Capture> reviewQueue(@RequestParam String region, @RequestParam(defaultValue = "100") int limit) {
        return new Collection<>(devices.reviewQueue(region, limit, callers.current()));
    }

    @PostMapping("/v1/device-captures/{id}/reviews")
    @PreAuthorize("hasAuthority('device.capture.review')")
    Capture review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest body) {
        return devices.review(id, body.accept(), body.ownerFileNumber(), callers.current());
    }
}

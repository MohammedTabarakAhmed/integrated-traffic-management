package gov.itms.device.application;

import gov.itms.device.domain.Capture;
import gov.itms.device.domain.Device;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Device-service repository and violation-service boundary. */
public final class Ports {

    private Ports() { }

    public interface DeviceRepository {
        void insert(Device device);
        Optional<Device> device(String code);
        Optional<Device> deviceForUpdate(String code);
        void updateCalibration(Device device, String by, Instant at);
        List<Device> devices(String region);
        boolean insertCapture(Capture capture);
        Optional<Capture> capture(UUID id);
        Optional<Capture> captureBySource(String sourceKey);
        Optional<Capture> captureForUpdate(UUID id);
        List<Capture> captures(Capture.Status status, String region, int limit);
        void updateCapture(Capture capture);
    }

    public interface ViolationRecorder {
        String record(Capture capture, String actorId);
    }

    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}

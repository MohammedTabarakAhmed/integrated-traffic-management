package gov.itms.device.infrastructure;

import gov.itms.device.application.Ports;
import gov.itms.device.domain.Capture;
import gov.itms.platform.client.ServiceCallException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Adapter to violation-service for accepted device reports (VF-010, VF-011). */
final class Adapters {

    private Adapters() { }

    static class RemoteViolationRecorder implements Ports.ViolationRecorder {
        private final RestClient client;

        RemoteViolationRecorder(RestClient client) {
            this.client = client;
        }

        record ViolationResponse(String number) { }

        @Override
        public String record(Capture capture, String actorId) {
            Map<String, Object> body = new HashMap<>();
            body.put("catalogueCode", capture.catalogueCode());
            body.put("occurredAt", capture.capturedAt().toString());
            body.put("region", capture.region());
            body.put("location", capture.location());
            body.put("plate", capture.plate());
            body.put("ownerFileNumber", capture.ownerFileNumber());
            body.put("speedLimitKmh", capture.speedLimitKmh());
            body.put("measuredSpeedKmh", capture.measuredSpeedKmh());
            body.put("source", "DEVICE");
            body.put("deviceId", capture.deviceCode());
            body.put("evidenceIds", List.of(capture.evidenceId()));
            try {
                ViolationResponse response = client.post().uri("/v1/violations").header("X-Channel", "DEVICE")
                        .header("Idempotency-Key", "device-capture-" + capture.id())
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                        .body(ViolationResponse.class);
                if (response == null || response.number() == null) {
                    throw new Ports.UnavailableException("violation was not recorded", null);
                }
                return response.number();
            } catch (RestClientException | ServiceCallException e) {
                throw new Ports.UnavailableException("violation service is unavailable", e);
            }
        }
    }
}

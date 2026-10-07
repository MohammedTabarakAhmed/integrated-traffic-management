package gov.itms.violation.infrastructure;

import gov.itms.platform.client.ServiceCallException;
import gov.itms.violation.application.Ports;
import gov.itms.violation.application.ViolationService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Adapters of violation-service: payment-service payables, hold-eligibility-service holds, payment events. */
final class Adapters {

    private Adapters() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(Adapters.class);

    /** {@code POST /v1/payables} and {@code /v1/payables/{id}/cancellations} with this service's own account. */
    static class RemotePayables implements Ports.Payables {

        private final RestClient client;

        RemotePayables(RestClient client) {
            this.client = client;
        }

        record PayableBody(UUID id) {
        }

        @Override
        public UUID register(String violationNumber, String fileNumber, String region, String currency,
                             long amountMinor, String financialItem, String beneficiary, String description) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("financialItem", financialItem);
            line.put("beneficiary", beneficiary);
            line.put("description", description.length() > 200 ? description.substring(0, 200) : description);
            line.put("amountMinor", amountMinor);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("itemType", "VIOLATION");
            body.put("itemRef", violationNumber);
            body.put("trafficFileNumber", fileNumber);
            body.put("region", region);
            body.put("currency", currency);
            body.put("lines", List.of(line));
            try {
                PayableBody p = client.post().uri("/v1/payables").header("X-Channel", "BACKOFFICE")
                        .header("Idempotency-Key", "payable-" + violationNumber.toLowerCase(java.util.Locale.ROOT))
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(PayableBody.class);
                if (p == null || p.id() == null) {
                    throw new Ports.UnavailableException("PAYMENTS_UNAVAILABLE", "payment cannot be prepared", null);
                }
                return p.id();
            } catch (RestClientException | ServiceCallException e) {
                LOG.warn("Payable for {} could not be registered: {}", violationNumber, e.getClass().getSimpleName());
                throw new Ports.UnavailableException("PAYMENTS_UNAVAILABLE", "payment cannot be prepared", e);
            }
        }

        @Override
        public void cancel(UUID payableId, String reason) {
            try {
                client.post().uri("/v1/payables/{id}/cancellations", payableId).header("X-Channel", "BACKOFFICE")
                        .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", reason)).retrieve()
                        .toBodilessEntity();
            } catch (RestClientException | ServiceCallException e) {
                LOG.warn("Payable {} could not be cancelled: {}", payableId, e.getClass().getSimpleName());
                throw new Ports.UnavailableException("PAYMENTS_UNAVAILABLE", "the fine cannot be withdrawn now", e);
            }
        }
    }

    /** {@code POST /v1/holds} of hold-eligibility-service (CC-030) for licence suspensions (VF-032, UAT-1). */
    static class RemoteHolds implements Ports.Holds {

        private final RestClient client;
        private final List<String> blockedServices;
        private final List<String> liftableByRoles;

        RemoteHolds(RestClient client, List<String> blockedServices, List<String> liftableByRoles) {
            this.client = client;
            this.blockedServices = List.copyOf(blockedServices);
            this.liftableByRoles = List.copyOf(liftableByRoles);
        }

        record HoldBody(String id) {
        }

        @Override
        public String placeSuspensionHold(String fileNumber, LocalDate from, LocalDate until, String sourceRef,
                                          String reason) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("subject", Map.of("type", "TRAFFIC_FILE", "id", fileNumber));
            body.put("holdType", "LICENCE_SUSPENSION");
            body.put("module", "VIOLATIONS");
            body.put("source", "BLACK_POINTS");
            body.put("sourceRef", sourceRef);
            body.put("reason", reason);
            body.put("startDate", from.toString());
            body.put("endDate", until.toString());
            body.put("blockedServices", blockedServices);
            body.put("liftableByRoles", liftableByRoles);
            try {
                HoldBody h = client.post().uri("/v1/holds").header("X-Channel", "BACKOFFICE")
                        .header("Idempotency-Key", "hold-" + sourceRef.toLowerCase(java.util.Locale.ROOT)
                                .replace(':', '-'))
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(HoldBody.class);
                if (h == null || h.id() == null) {
                    throw new Ports.UnavailableException("HOLDS_UNAVAILABLE", "the suspension hold was not placed",
                            null);
                }
                return h.id();
            } catch (RestClientException | ServiceCallException e) {
                LOG.warn("Suspension hold for a file could not be placed: {}", e.getClass().getSimpleName());
                throw new Ports.UnavailableException("HOLDS_UNAVAILABLE", "the suspension hold was not placed", e);
            }
        }
    }

    /** Marks violations paid or open again from payment-service events (VF-070, UAT-4, FN-011). */
    static class PaymentEventListener {

        private final ViolationService violations;
        private final ObjectMapper json;

        PaymentEventListener(ViolationService violations, ObjectMapper json) {
            this.violations = violations;
            this.json = json;
        }

        @KafkaListener(topics = "${itms.violations.payment-topic:itms.payment-service.events.v1}",
                groupId = "${itms.violations.consumer-group:violation-service}")
        void onMessage(String message) {
            JsonNode event;
            try {
                event = json.readTree(message);
            } catch (RuntimeException e) {
                LOG.warn("Skipping a payment event that is not JSON");
                return;
            }
            String type = event.path("type").asString("");
            boolean confirmed = "itms.payment.confirmed".equals(type);
            if (!confirmed && !"itms.payment.receipt-cancelled".equals(type)) {
                return;
            }
            for (JsonNode item : event.path("data").path("items")) {
                if ("VIOLATION".equals(item.path("itemType").asString(""))) {
                    String number = item.path("itemRef").asString("");
                    if (confirmed) {
                        violations.onPaid(number);
                    } else {
                        violations.onReceiptCancelled(number);
                    }
                }
            }
        }
    }
}

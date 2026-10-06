package gov.itms.vehicle.infrastructure;

import gov.itms.platform.client.ServiceCallException;
import gov.itms.vehicle.application.Ports;
import gov.itms.vehicle.application.VehicleService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Adapters of vehicle-service: insurers (ICD-02), fees-service, payment-service and the payment events. */
final class Adapters {

    private Adapters() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(Adapters.class);

    /**
     * ICD-02 {@code GET /v1/policies?vin=&asOf=} through the insurance hub (or its mock). 404 means no policy; any
     * other failure is "unavailable", and registration is blocked (fail closed, ICD-02).
     */
    static class HttpInsuranceRegister implements Ports.InsuranceRegister {

        private final RestClient client;

        HttpInsuranceRegister(RestClient client) {
            this.client = client;
        }

        record PolicyBody(String policyNo, String insurerCode, String vin, String coverType, LocalDate validFrom,
                          LocalDate validTo, String status) {
        }

        record Answer(List<PolicyBody> items) {
        }

        @Override
        public List<Policy> policies(String vin, LocalDate asOf) {
            Answer a;
            try {
                a = client.get().uri(b -> b.path("/v1/policies").queryParam("vin", vin)
                        .queryParam("asOf", asOf.toString()).build()).retrieve().body(Answer.class);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404))) {
                    return List.of();
                }
                LOG.warn("Insurance hub answered {}", e.getStatusCode().value());
                throw unavailable(e);
            } catch (RestClientException e) {
                LOG.warn("Insurance hub did not answer: {}", e.getClass().getSimpleName());
                throw unavailable(e);
            }
            if (a == null || a.items() == null) {
                throw unavailable(null);
            }
            return a.items().stream().filter(p -> vin.equals(p.vin()) && p.validFrom() != null && p.validTo() != null)
                    .map(p -> new Policy(p.policyNo(), p.insurerCode(), p.coverType(), p.validFrom(), p.validTo(),
                            p.status())).toList();
        }

        private static Ports.UnavailableException unavailable(Throwable cause) {
            return new Ports.UnavailableException("INSURANCE_UNAVAILABLE",
                    "insurance could not be verified, try later", cause);
        }
    }

    /** fees-service {@code POST /v1/quotes} with this service's own account (ADR-034). */
    static class RemoteFeeQuotes implements Ports.FeeQuotes {

        private final RestClient client;

        RemoteFeeQuotes(RestClient client) {
            this.client = client;
        }

        record LineBody(String financialItem, String beneficiary, String nameEn, long amount) {
        }

        record QuoteBody(String id, List<LineBody> lines, long total, String currency) {
        }

        @Override
        public Quote quote(String serviceCode, String region, String customerCategory, String subjectCategory,
                           LocalDate expiryDate) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("serviceCode", serviceCode);
            body.put("region", region);
            body.put("customerCategory", customerCategory);
            body.put("subjectCategory", subjectCategory);
            if (expiryDate != null) {
                body.put("expiryDate", expiryDate.toString());
            }
            QuoteBody q;
            try {
                q = client.post().uri("/v1/quotes").header("X-Channel", "BACKOFFICE")
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(QuoteBody.class);
            } catch (RestClientException | ServiceCallException e) {
                LOG.warn("Fee quote for {} failed: {}", serviceCode, e.getClass().getSimpleName());
                throw new Ports.UnavailableException("FEES_UNAVAILABLE", "the fee cannot be calculated now", e);
            }
            if (q == null || q.id() == null || q.lines() == null || q.lines().isEmpty() || q.currency() == null) {
                throw new Ports.UnavailableException("FEES_UNAVAILABLE", "the fee cannot be calculated now", null);
            }
            return new Quote(q.id(), q.lines().stream().filter(l -> l.amount() > 0).map(l -> new Line(
                    l.financialItem(), l.beneficiary(), l.nameEn(), l.amount())).toList(), q.total(), q.currency());
        }
    }

    /** payment-service {@code POST /v1/payables}; the Idempotency-Key is derived from the item, so retries are safe. */
    static class RemotePayables implements Ports.Payables {

        private final RestClient client;

        RemotePayables(RestClient client) {
            this.client = client;
        }

        record PayableBody(UUID id) {
        }

        @Override
        public UUID register(String itemRef, String trafficFileNumber, String region, String currency,
                             List<Ports.FeeQuotes.Line> lines) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("itemType", "TRANSACTION");
            body.put("itemRef", itemRef);
            body.put("trafficFileNumber", trafficFileNumber);
            body.put("region", region);
            body.put("currency", currency);
            body.put("lines", lines.stream().map(l -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("financialItem", l.financialItem());
                m.put("beneficiary", l.beneficiary());
                m.put("description", l.description());
                m.put("amountMinor", l.amountMinor());
                return m;
            }).toList());
            PayableBody p;
            try {
                p = client.post().uri("/v1/payables").header("X-Channel", "BACKOFFICE")
                        .header("Idempotency-Key", "payable-" + itemRef.toLowerCase(java.util.Locale.ROOT))
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(PayableBody.class);
            } catch (RestClientException | ServiceCallException e) {
                LOG.warn("Payable {} could not be registered: {}", itemRef, e.getClass().getSimpleName());
                throw new Ports.UnavailableException("PAYMENTS_UNAVAILABLE", "payment cannot be prepared now", e);
            }
            if (p == null || p.id() == null) {
                throw new Ports.UnavailableException("PAYMENTS_UNAVAILABLE", "payment cannot be prepared now", null);
            }
            return p.id();
        }
    }

    /** Completes registrations when payment-service confirms their payables ({@code itms.payment.confirmed}). */
    static class PaymentEventListener {

        private final VehicleService vehicles;
        private final ObjectMapper json;

        PaymentEventListener(VehicleService vehicles, ObjectMapper json) {
            this.vehicles = vehicles;
            this.json = json;
        }

        @KafkaListener(topics = "${itms.vehicles.payment-topic:itms.payment-service.events.v1}",
                groupId = "${itms.vehicles.consumer-group:vehicle-service}")
        void onMessage(String message) {
            JsonNode event;
            try {
                event = json.readTree(message);
            } catch (RuntimeException e) {
                LOG.warn("Skipping a payment event that is not JSON");
                return;
            }
            if (!"itms.payment.confirmed".equals(event.path("type").asString(""))) {
                return;
            }
            for (JsonNode item : event.path("data").path("items")) {
                if ("TRANSACTION".equals(item.path("itemType").asString(""))) {
                    vehicles.onPaid(item.path("itemRef").asString(""));
                }
            }
        }
    }
}

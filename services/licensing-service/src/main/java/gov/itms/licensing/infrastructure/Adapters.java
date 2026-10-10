package gov.itms.licensing.infrastructure;

import gov.itms.licensing.application.LicenceService;
import gov.itms.licensing.application.LicenceTransactions;
import gov.itms.licensing.application.Ports;
import gov.itms.platform.client.ServiceCallException;
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

/** Adapters of licensing-service: fees-service, payment-service, hold-eligibility-service and the payment events. */
final class Adapters {

    private Adapters() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(Adapters.class);

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
                           Map<String, Integer> quantities, LocalDate expiryDate) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("serviceCode", serviceCode);
            body.put("region", region);
            body.put("customerCategory", customerCategory);
            body.put("subjectCategory", subjectCategory);
            if (quantities != null && !quantities.isEmpty()) {
                Map<String, String> q = new LinkedHashMap<>();
                quantities.forEach((k, v) -> q.put(k, String.valueOf(v)));
                body.put("quantities", q);
            }
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

    /**
     * {@code POST /v1/eligibility-checks} of hold-eligibility-service (CC-032) with this service's own account
     * (role ELIGIBILITY_CHECKER). Anything but a complete 200 answer for the service asked about is "unavailable", so
     * a licence transaction is never let through unchecked (DL-050).
     */
    static class RemoteEligibility implements Ports.Eligibility {

        private final RestClient client;

        RemoteEligibility(RestClient client) {
            this.client = client;
        }

        record Answer(String serviceCode, Boolean eligible, List<Block> blocks) {
        }

        @Override
        public List<Block> blocks(String serviceCode, List<Subject> subjects, String channel) {
            Answer answer;
            try {
                answer = client.post().uri("/v1/eligibility-checks").header("X-Channel", channel)
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .body(Map.of("serviceCode", serviceCode, "subjects", subjects.stream()
                                .map(s -> Map.of("type", s.type(), "id", s.id())).toList()))
                        .retrieve().body(Answer.class);
            } catch (RestClientException | ServiceCallException e) {
                LOG.warn("Eligibility check for {} failed: {}", serviceCode, e.getClass().getSimpleName());
                throw unavailable(e);
            }
            if (answer == null || !serviceCode.equals(answer.serviceCode()) || answer.eligible() == null
                    || answer.blocks() == null || answer.eligible() != answer.blocks().isEmpty()
                    || answer.blocks().stream().anyMatch(b -> b == null || b.reasonCode() == null
                            || b.nextStep() == null || b.message() == null || b.message().isBlank())) {
                LOG.warn("Eligibility check for {}: incomplete answer", serviceCode);
                throw unavailable(null);
            }
            return List.copyOf(answer.blocks());
        }

        private static Ports.UnavailableException unavailable(Throwable cause) {
            return new Ports.UnavailableException("ELIGIBILITY_UNAVAILABLE", "eligibility cannot be checked right now",
                    cause);
        }
    }

    /** Completes licensing requests when payment-service confirms their payables ({@code itms.payment.confirmed}). */
    static class PaymentEventListener {

        private final LicenceService licences;
        private final ObjectMapper json;

        PaymentEventListener(LicenceService licences, ObjectMapper json) {
            this.licences = licences;
            this.json = json;
        }

        @KafkaListener(topics = "${itms.licensing.payment-topic:itms.payment-service.events.v1}",
                groupId = "${itms.licensing.consumer-group:licensing-service}")
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
                    String itemRef = item.path("itemRef").asString("");
                    try {
                        licences.onPaid(itemRef);
                    } catch (RuntimeException e) {
                        // The payment stays recorded at payment-service; staff complete or refund the request.
                        LOG.error("Paid licensing request {} could not be completed: {}", itemRef, e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Follows traffic-file-service ({@code itms.traffic-file.identity-updated}, DL-057, DL-010): the licences of a
     * person whose register identity changed get a new card or a history line. Each CloudEvent id is applied once.
     */
    static class TrafficFileEventListener {

        private final LicenceTransactions transactions;
        private final ObjectMapper json;

        TrafficFileEventListener(LicenceTransactions transactions, ObjectMapper json) {
            this.transactions = transactions;
            this.json = json;
        }

        @KafkaListener(topics = "${itms.licensing.traffic-file-topic:itms.traffic-file-service.events.v1}",
                groupId = "${itms.licensing.consumer-group:licensing-service}")
        void onMessage(String message) {
            JsonNode event;
            try {
                event = json.readTree(message);
            } catch (RuntimeException e) {
                LOG.warn("Skipping a traffic file event that is not JSON");
                return;
            }
            String type = event.path("type").asString("");
            String id = event.path("id").asString("");
            String file = event.path("subject").asString("");
            if (id.isEmpty() || file.isEmpty()) {
                return;
            }
            if ("itms.traffic-file.identity-updated".equals(type)) {
                transactions.onRegisterChange(id, file, event.path("data").path("changeType").asString(""));
            }
        }
    }
}

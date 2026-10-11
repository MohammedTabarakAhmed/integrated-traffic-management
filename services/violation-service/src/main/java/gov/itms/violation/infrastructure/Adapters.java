package gov.itms.violation.infrastructure;

import gov.itms.platform.client.ServiceCallException;
import gov.itms.violation.application.InstalmentService;
import gov.itms.violation.application.Ports;
import gov.itms.violation.application.ViolationService;
import gov.itms.violation.domain.UnpaidHold;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
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
            return register(violationNumber, fileNumber, region, currency, amountMinor, financialItem, beneficiary,
                    description, "payable-" + violationNumber.toLowerCase(java.util.Locale.ROOT));
        }

        @Override
        public UUID registerReduced(String violationNumber, String fileNumber, String region, String currency,
                                    long amountMinor, String financialItem, String beneficiary, String description) {
            // A key of its own: the original payable's key would replay the original payable (ADR-032).
            return register(violationNumber, fileNumber, region, currency, amountMinor, financialItem, beneficiary,
                    description, "payable-reduced-" + violationNumber.toLowerCase(java.util.Locale.ROOT));
        }

        @Override
        public UUID registerInstalment(String itemRef, String fileNumber, String region, String currency,
                                       List<Ports.Payables.Line> lines) {
            return register(itemRef, fileNumber, region, currency, lines,
                    "payable-" + itemRef.toLowerCase(java.util.Locale.ROOT));
        }

        @SuppressWarnings("checkstyle:ParameterNumber")
        private UUID register(String violationNumber, String fileNumber, String region, String currency,
                              long amountMinor, String financialItem, String beneficiary, String description,
                              String idempotencyKey) {
            return register(violationNumber, fileNumber, region, currency,
                    List.of(new Ports.Payables.Line(financialItem, beneficiary, description, amountMinor)),
                    idempotencyKey);
        }

        /** A VIOLATION payable; an instalment's item reference is {@code <plan>-<nn>} (VF-071). */
        private UUID register(String violationNumber, String fileNumber, String region, String currency,
                              List<Ports.Payables.Line> lines, String idempotencyKey) {
            List<Map<String, Object>> bodyLines = lines.stream().map(l -> {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("financialItem", l.financialItem());
                line.put("beneficiary", l.beneficiary());
                line.put("description", l.description().length() > 200 ? l.description().substring(0, 200)
                        : l.description());
                line.put("amountMinor", l.amountMinor());
                return line;
            }).toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("itemType", "VIOLATION");
            body.put("itemRef", violationNumber);
            body.put("trafficFileNumber", fileNumber);
            body.put("region", region);
            body.put("currency", currency);
            body.put("lines", bodyLines);
            try {
                PayableBody p = client.post().uri("/v1/payables").header("X-Channel", "BACKOFFICE")
                        .header("Idempotency-Key", idempotencyKey)
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

        @Override
        public void hold(UUID payableId, String sourceRef, String reason) {
            try {
                client.post().uri("/v1/payables/{id}/holds", payableId).header("X-Channel", "BACKOFFICE")
                        .header("Idempotency-Key", "payable-hold-" + sourceRef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("sourceRef", sourceRef, "reason", reason)).retrieve().toBodilessEntity();
            } catch (RestClientException | ServiceCallException e) {
                throw new Ports.UnavailableException("PAYMENTS_UNAVAILABLE", "the fine cannot be paused now", e);
            }
        }

        @Override
        public void liftHold(UUID payableId, String sourceRef, String reason) {
            try {
                client.post().uri("/v1/payables/{id}/hold-lifts", payableId).header("X-Channel", "BACKOFFICE")
                        .header("Idempotency-Key", "payable-hold-lift-" + sourceRef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("sourceRef", sourceRef, "reason", reason)).retrieve().toBodilessEntity();
            } catch (RestClientException | ServiceCallException e) {
                throw new Ports.UnavailableException("PAYMENTS_UNAVAILABLE", "the fine cannot be resumed now", e);
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

    /**
     * {@code POST /v1/holds} and {@code /v1/holds/{id}/lifts} of hold-eligibility-service (CC-030) for violations
     * left unpaid (VF-023): an open-ended UNPAID_FINES hold in module VIOLATIONS, lifted by this service once the
     * violation is paid or cancelled. A hold that is no longer active (409) counts as lifted.
     */
    static class RemoteUnpaidFineHolds implements Ports.UnpaidFineHolds {

        private final RestClient client;
        private final List<String> blockedServices;
        private final List<String> liftableByRoles;

        RemoteUnpaidFineHolds(RestClient client, List<String> blockedServices, List<String> liftableByRoles) {
            this.client = client;
            this.blockedServices = List.copyOf(blockedServices);
            this.liftableByRoles = List.copyOf(liftableByRoles);
        }

        @Override
        public String place(UnpaidHold.Subject subject, String sourceRef, String reason) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("subject", Map.of("type", subject.type().name(), "id", subject.id()));
            body.put("holdType", "UNPAID_FINES");
            body.put("module", "VIOLATIONS");
            body.put("source", "UNPAID_VIOLATION");
            body.put("sourceRef", sourceRef);
            body.put("reason", reason);
            body.put("blockedServices", blockedServices);
            body.put("liftableByRoles", liftableByRoles);
            try {
                RemoteHolds.HoldBody h = client.post().uri("/v1/holds").header("X-Channel", "BACKOFFICE")
                        .header("Idempotency-Key", "hold-" + sourceRef.toLowerCase(Locale.ROOT))
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                        .body(RemoteHolds.HoldBody.class);
                if (h == null || h.id() == null) {
                    throw new Ports.UnavailableException("HOLDS_UNAVAILABLE", "the unpaid hold was not placed", null);
                }
                return h.id();
            } catch (RestClientException | ServiceCallException e) {
                LOG.warn("Unpaid hold could not be placed: {}", e.getClass().getSimpleName());
                throw new Ports.UnavailableException("HOLDS_UNAVAILABLE", "the unpaid hold was not placed", e);
            }
        }

        @Override
        public void lift(String holdId, String reason) {
            try {
                client.post().uri("/v1/holds/{id}/lifts", holdId).header("X-Channel", "BACKOFFICE")
                        .header("Idempotency-Key", "lift-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", reason)).retrieve()
                        .toBodilessEntity();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 409) {
                    return; // already lifted or expired
                }
                LOG.warn("Unpaid hold could not be lifted: HTTP {}", e.getStatusCode().value());
                throw new Ports.UnavailableException("HOLDS_UNAVAILABLE", "the unpaid hold was not lifted", e);
            } catch (RestClientException | ServiceCallException e) {
                LOG.warn("Unpaid hold could not be lifted: {}", e.getClass().getSimpleName());
                throw new Ports.UnavailableException("HOLDS_UNAVAILABLE", "the unpaid hold was not lifted", e);
            }
        }
    }

    /**
     * Marks violations paid or open again from payment-service events (VF-070, UAT-4, FN-011); an item reference
     * {@code IP-...-nn} is an instalment of a plan (VF-073).
     */
    static class PaymentEventListener {

        private final ViolationService violations;
        private final InstalmentService instalments;
        private final ObjectMapper json;

        PaymentEventListener(ViolationService violations, InstalmentService instalments, ObjectMapper json) {
            this.violations = violations;
            this.instalments = instalments;
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
                    if (number.startsWith(InstalmentService.PLAN_PREFIX)) {
                        if (confirmed) {
                            instalments.onInstalmentPaid(number);
                        } else {
                            instalments.onInstalmentReceiptCancelled(number);
                        }
                    } else if (confirmed) {
                        violations.onPaid(number);
                    } else {
                        violations.onReceiptCancelled(number);
                    }
                }
            }
        }
    }

    /** {@code GET /v1/vehicles?plate=} at vehicle-service with this service's own account (XB-002). */
    static class RemoteVehicles implements Ports.Vehicles {

        private final RestClient client;

        RemoteVehicles(RestClient client) {
            this.client = client;
        }

        record VehicleBody(String vin, String ownerFileNumber, String region) {
        }

        @Override
        public java.util.Optional<Registered> byPlate(String plate) {
            try {
                VehicleBody v = client.get().uri(b -> b.path("/v1/vehicles").queryParam("plate", plate).build())
                        .header("X-Channel", "BACKOFFICE").retrieve().body(VehicleBody.class);
                return v == null ? java.util.Optional.empty()
                        : java.util.Optional.of(new Registered(v.vin(), v.ownerFileNumber(), v.region()));
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 422) {
                    return java.util.Optional.empty();
                }
                LOG.warn("Vehicle lookup failed: HTTP {}", e.getStatusCode().value());
                throw new Ports.UnavailableException("VEHICLES_UNAVAILABLE", "the vehicle cannot be found now", e);
            } catch (RestClientException | ServiceCallException e) {
                LOG.warn("Vehicle lookup failed: {}", e.getClass().getSimpleName());
                throw new Ports.UnavailableException("VEHICLES_UNAVAILABLE", "the vehicle cannot be found now", e);
            }
        }
    }

    /** {@code GET /v1/rental-contracts/coverage} at rental-service with this service's own account (TR-032). */
    static class RemoteRentals implements Ports.Rentals {

        private final RestClient client;

        RemoteRentals(RestClient client) {
            this.client = client;
        }

        record CoverageBody(boolean covered, java.util.UUID contractId, String companyFile, String renterFile,
                            boolean visitor) {
        }

        @Override
        public java.util.Optional<Cover> covering(String plate, java.time.Instant at) {
            try {
                CoverageBody c = client.get().uri(b -> b.path("/v1/rental-contracts/coverage")
                                .queryParam("plate", plate).queryParam("at", at.toString()).build())
                        .header("X-Channel", "BACKOFFICE").retrieve().body(CoverageBody.class);
                if (c == null || !c.covered()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(new Cover(c.contractId(), c.companyFile(), c.renterFile(), c.visitor()));
            } catch (RestClientResponseException e) {
                LOG.warn("Rental coverage lookup failed: HTTP {}", e.getStatusCode().value());
                throw new Ports.UnavailableException("RENTALS_UNAVAILABLE", "rental contracts cannot be checked now",
                        e);
            } catch (RestClientException | ServiceCallException e) {
                LOG.warn("Rental coverage lookup failed: {}", e.getClass().getSimpleName());
                throw new Ports.UnavailableException("RENTALS_UNAVAILABLE", "rental contracts cannot be checked now",
                        e);
            }
        }
    }

    /**
     * crossborder-service events (Increment 9): partner violations to load (XB-002), payments made in the partner state
     * (XB-003), objections forwarded to or received from a partner and the partner's decisions (XB-005). Business
     * refusals are logged and skipped; an unavailable dependency is rethrown so the message is retried.
     */
    static class CrossborderEventListener {

        private final gov.itms.violation.application.CrossborderViolations crossborder;
        private final gov.itms.violation.application.ObjectionService objections;
        private final ObjectMapper json;

        CrossborderEventListener(gov.itms.violation.application.CrossborderViolations crossborder,
                                 gov.itms.violation.application.ObjectionService objections, ObjectMapper json) {
            this.crossborder = crossborder;
            this.objections = objections;
            this.json = json;
        }

        @KafkaListener(topics = "${itms.violations.crossborder-topic:itms.crossborder-service.events.v1}",
                groupId = "${itms.violations.consumer-group:violation-service}")
        void onMessage(String message) {
            JsonNode event;
            try {
                event = json.readTree(message);
            } catch (RuntimeException e) {
                LOG.warn("Skipping a cross-border event that is not JSON");
                return;
            }
            JsonNode d = event.path("data");
            String partner = d.path("partner").asString("");
            String local = d.path("localReference").asString("");
            try {
                switch (event.path("type").asString("")) {
                    case "itms.crossborder.violation-received" -> crossborder.loadFromPartner(
                            new gov.itms.violation.application.CrossborderViolations.PartnerViolation(partner,
                                    d.path("externalId").asString(""), d.path("localCode").asString(""),
                                    instant(d.path("occurredAt").asString("")), d.path("location").asString(""),
                                    d.path("plate").asString(""), d.path("currency").asString(""),
                                    d.path("amountMinor").asLong(-1)));
                    case "itms.crossborder.payment-applied" -> {
                        if (!local.isEmpty()) {
                            crossborder.paidInPartnerState(local, partner, d.path("paymentReference").asString(""));
                        }
                    }
                    case "itms.crossborder.objection-submitted" -> {
                        if (!local.isEmpty()) {
                            crossborder.objectionForwarded(local, partner);
                        }
                    }
                    case "itms.crossborder.objection-decided" -> {
                        if (!local.isEmpty()) {
                            boolean accepted = "ACCEPTED".equals(d.path("decision").asString(""));
                            String reason = d.path("reason").asString("");
                            objections.decidedByPartner(local, partner, accepted, reason);
                            crossborder.partnerDecided(local, partner, accepted, reason);
                        }
                    }
                    case "itms.crossborder.objection-received" -> {
                        if (!local.isEmpty()) {
                            objections.submitFromPartner(local, partner, d.path("reason").asString(""));
                        }
                    }
                    default -> {
                        // other cross-border events are not for violations
                    }
                }
            } catch (gov.itms.violation.domain.DomainException e) {
                LOG.warn("Cross-border event {} refused: {}", event.path("id").asString(""), e.code());
            }
        }

        private static java.time.Instant instant(String value) {
            try {
                return java.time.Instant.parse(value);
            } catch (java.time.format.DateTimeParseException e) {
                return null;
            }
        }
    }
}

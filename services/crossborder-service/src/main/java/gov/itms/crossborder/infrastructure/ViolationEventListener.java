package gov.itms.crossborder.infrastructure;

import gov.itms.crossborder.application.CrossborderService;
import gov.itms.crossborder.domain.DomainException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * violation-service events that drive the exchange: violations by partner-registered vehicles to send (XB-001), load
 * results (XB-002), local payments to notify (XB-003, UAT-5), customer objections to forward and local decisions on
 * partner objections (XB-005). Business refusals are logged and skipped; other failures are retried by the consumer.
 */
class ViolationEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(ViolationEventListener.class);

    private final CrossborderService exchange;
    private final ObjectMapper json;

    ViolationEventListener(CrossborderService exchange, ObjectMapper json) {
        this.exchange = exchange;
        this.json = json;
    }

    @KafkaListener(topics = "${itms.crossborder.violation-topic:itms.violation-service.events.v1}",
            groupId = "${itms.crossborder.consumer-group:crossborder-service}")
    void onMessage(String message) {
        JsonNode event;
        try {
            event = json.readTree(message);
        } catch (RuntimeException e) {
            LOG.warn("Skipping a violation event that is not JSON");
            return;
        }
        JsonNode d = event.path("data");
        String number = d.path("violationNumber").asString("");
        String partner = d.path("partner").asString("");
        try {
            switch (event.path("type").asString("")) {
                case "itms.violation.crossborder-recorded" -> exchange.sendViolation(partner, number,
                        d.path("catalogueCode").asString(""), d.path("plate").asString(null),
                        d.path("ownerFileNumber").asString(null), d.path("currency").asString(""),
                        d.path("amountMinor").asLong(-1), instant(d.path("occurredAt").asString("")),
                        d.path("location").asString(null), null);
                case "itms.violation.crossborder-loaded" -> exchange.violationLoaded(partner,
                        d.path("externalId").asString(""), number);
                case "itms.violation.crossborder-rejected" -> exchange.violationRejected(partner,
                        d.path("externalId").asString(""), d.path("reasonCode").asString(""));
                case "itms.violation.paid" -> exchange.paidHere(number);
                case "itms.violation.crossborder-objection" -> exchange.forwardObjection(partner, null, number,
                        d.path("reason").asString(""), false, null);
                case "itms.violation.objection-decided" -> exchange.localObjectionDecided(number,
                        d.path("decision").asString(""), d.path("reason").asString(""));
                default -> {
                    // other violation events do not cross the border
                }
            }
        } catch (DomainException e) {
            LOG.warn("Violation event {} not exchanged: {}", event.path("id").asString(""), e.code());
        }
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

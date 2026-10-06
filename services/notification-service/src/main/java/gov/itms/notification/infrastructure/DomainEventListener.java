package gov.itms.notification.infrastructure;

import gov.itms.notification.application.NotificationService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes the other services' domain events (CloudEvents JSON, ADR-002) and hands them to
 * {@link NotificationService#onEvent} (CC-071). Redeliveries are ignored there (processed_event). A malformed event is
 * logged and skipped, so one bad message cannot block the topic.
 */
class DomainEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(DomainEventListener.class);

    private final NotificationService notifications;
    private final ObjectMapper json;

    DomainEventListener(NotificationService notifications, ObjectMapper json) {
        this.notifications = notifications;
        this.json = json;
    }

    @KafkaListener(topics = "#{'${itms.notifications.consumer.topics}'.split(',')}",
            groupId = "${itms.notifications.consumer.group-id:notification-service}")
    void onMessage(String message) {
        JsonNode event;
        try {
            event = json.readTree(message);
        } catch (RuntimeException e) {
            LOG.warn("Skipping an event that is not JSON");
            return;
        }
        if (!event.hasNonNull("id") || !event.hasNonNull("type")) {
            LOG.warn("Skipping an event without id or type");
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        JsonNode d = event.get("data");
        if (d != null && d.isObject()) {
            d.properties().forEach(e -> {
                JsonNode v = e.getValue();
                if (v.isValueNode() && !v.isNull()) {
                    data.put(e.getKey(), v.isNumber() ? v.numberValue() : v.isBoolean() ? v.booleanValue()
                            : v.asString());
                }
            });
        }
        notifications.onEvent(event.get("id").asString(), event.get("type").asString(), data);
    }
}

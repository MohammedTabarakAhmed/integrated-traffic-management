package gov.itms.platform.events;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds CloudEvents 1.0 structured-mode JSON envelopes (ADR-002). Extension attribute names follow the CloudEvents
 * rule (lower-case letters and digits only): {@code correlationid}, {@code actorid}.
 */
public final class CloudEvents {

    public static final String SPEC_VERSION = "1.0";
    public static final String CONTENT_TYPE = "application/cloudevents+json";

    private CloudEvents() {
    }

    /**
     * @param dataJson the event payload, already serialised as a JSON document
     */
    public static String envelope(ObjectMapper json, String id, String service, String type, String subject,
                                  Instant time, String dataJson, String actorId, String correlationId) {
        ObjectNode node = json.createObjectNode();
        node.put("specversion", SPEC_VERSION);
        node.put("id", id);
        node.put("source", source(service));
        node.put("type", type);
        node.put("subject", subject);
        node.put("time", time.truncatedTo(ChronoUnit.MICROS).toString());
        node.put("datacontenttype", "application/json");
        if (correlationId != null) {
            node.put("correlationid", correlationId);
        }
        if (actorId != null) {
            node.put("actorid", actorId);
        }
        node.set("data", json.readTree(dataJson));
        return json.writeValueAsString(node);
    }

    public static String source(String service) {
        return "/itms/" + service;
    }
}

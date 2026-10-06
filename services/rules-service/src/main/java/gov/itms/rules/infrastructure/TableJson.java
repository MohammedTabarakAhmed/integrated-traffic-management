package gov.itms.rules.infrastructure;

import gov.itms.rules.domain.DecisionTable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Stores a {@link DecisionTable} and rule values as JSON; numbers keep their exact decimal value. */
final class TableJson {

    private TableJson() {
    }

    static String write(ObjectMapper json, DecisionTable t) {
        ObjectNode n = json.createObjectNode();
        n.put("hitPolicy", t.hitPolicy().name());
        n.set("inputs", columns(json, t.inputs()));
        n.set("outputs", columns(json, t.outputs()));
        ArrayNode rules = n.putArray("rules");
        for (DecisionTable.Rule r : t.rules()) {
            ObjectNode rn = rules.addObject();
            ObjectNode when = rn.putObject("when");
            r.when().forEach((k, c) -> {
                ObjectNode cn = when.putObject(k);
                cn.put("op", c.op().name());
                ArrayNode values = cn.putArray("values");
                c.values().forEach(v -> values.add(json.valueToTree(v)));
            });
            rn.set("then", json.valueToTree(r.then()));
            if (r.description() != null) {
                rn.put("description", r.description());
            }
        }
        n.set("defaults", json.valueToTree(t.defaults()));
        return json.writeValueAsString(n);
    }

    static DecisionTable read(ObjectMapper json, String text) {
        JsonNode n = json.readTree(text);
        List<DecisionTable.Rule> rules = new ArrayList<>();
        for (JsonNode rn : n.get("rules")) {
            Map<String, DecisionTable.Condition> when = new LinkedHashMap<>();
            rn.get("when").properties().forEach(e -> {
                List<Object> values = new ArrayList<>();
                e.getValue().get("values").forEach(v -> values.add(value(v)));
                when.put(e.getKey(), new DecisionTable.Condition(
                        DecisionTable.Op.valueOf(e.getValue().get("op").asString()), values));
            });
            rules.add(new DecisionTable.Rule(when, values(rn.get("then")),
                    rn.hasNonNull("description") ? rn.get("description").asString() : null));
        }
        return new DecisionTable(columns(n.get("inputs")), columns(n.get("outputs")),
                DecisionTable.HitPolicy.valueOf(n.get("hitPolicy").asString()), rules, values(n.get("defaults")));
    }

    private static ArrayNode columns(ObjectMapper json, List<DecisionTable.Column> columns) {
        ArrayNode a = json.createArrayNode();
        columns.forEach(c -> a.addObject().put("name", c.name()).put("type", c.type().name()));
        return a;
    }

    private static List<DecisionTable.Column> columns(JsonNode a) {
        List<DecisionTable.Column> columns = new ArrayList<>();
        a.forEach(c -> columns.add(new DecisionTable.Column(c.get("name").asString(),
                DecisionTable.Type.valueOf(c.get("type").asString()))));
        return columns;
    }

    /** A JSON object of scalar values as a map (numbers as BigDecimal). */
    static Map<String, Object> values(JsonNode object) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (object != null && object.isObject()) {
            object.properties().forEach(e -> m.put(e.getKey(), value(e.getValue())));
        }
        return m;
    }

    static Object value(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        return v.asString();
    }
}

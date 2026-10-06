package gov.itms.fees.infrastructure;

import gov.itms.fees.domain.FeeMethod;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Stores a {@link FeeMethod} as JSON ({@code fee_version.method}); decimals are strings, so nothing is rounded. */
final class FeeMethodJson {

    private FeeMethodJson() {
    }

    static String write(ObjectMapper json, FeeMethod method) {
        ObjectNode n = json.createObjectNode();
        switch (method) {
            case FeeMethod.Fixed f -> {
                n.put("type", "FIXED");
                n.put("amount", f.amount());
            }
            case FeeMethod.PerUnit p -> {
                n.put("type", "PER_UNIT");
                n.put("unit", p.unit());
                n.put("rate", p.rate().toPlainString());
                if (p.minimum() != null) {
                    n.put("minimum", p.minimum());
                }
                if (p.maximum() != null) {
                    n.put("maximum", p.maximum());
                }
            }
            case FeeMethod.Tiered t -> {
                n.put("type", "TIERED");
                n.put("unit", t.unit());
                n.put("mode", t.mode().name());
                ArrayNode tiers = n.putArray("tiers");
                for (FeeMethod.Tiered.Tier tier : t.tiers()) {
                    ObjectNode tn = tiers.addObject();
                    if (tier.upTo() == null) {
                        tn.putNull("upTo");
                    } else {
                        tn.put("upTo", tier.upTo().toPlainString());
                    }
                    tn.put("rate", tier.rate().toPlainString());
                }
            }
            case FeeMethod.Formula f -> {
                n.put("type", "FORMULA");
                n.put("expression", f.expression());
            }
        }
        return json.writeValueAsString(n);
    }

    static FeeMethod read(ObjectMapper json, String text) {
        JsonNode n = json.readTree(text);
        return switch (n.get("type").asString()) {
            case "FIXED" -> new FeeMethod.Fixed(n.get("amount").asLong());
            case "PER_UNIT" -> new FeeMethod.PerUnit(n.get("unit").asString(), new BigDecimal(n.get("rate").asString()),
                    n.hasNonNull("minimum") ? n.get("minimum").asLong() : null,
                    n.hasNonNull("maximum") ? n.get("maximum").asLong() : null);
            case "TIERED" -> {
                List<FeeMethod.Tiered.Tier> tiers = new ArrayList<>();
                for (JsonNode tn : n.get("tiers")) {
                    tiers.add(new FeeMethod.Tiered.Tier(
                            tn.get("upTo").isNull() ? null : new BigDecimal(tn.get("upTo").asString()),
                            new BigDecimal(tn.get("rate").asString())));
                }
                yield new FeeMethod.Tiered(n.get("unit").asString(),
                        FeeMethod.Tiered.Mode.valueOf(n.get("mode").asString()), tiers);
            }
            case "FORMULA" -> new FeeMethod.Formula(n.get("expression").asString());
            default -> throw new IllegalStateException("unknown fee method " + n.get("type"));
        };
    }
}

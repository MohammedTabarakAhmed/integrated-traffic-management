package gov.itms.rules.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A business rule as a decision table (CC-050; ADR-048), in the style of DMN: typed inputs, typed outputs, rules made
 * of one condition per input and one value per output, and a hit policy. Conditions are structured (operator and
 * values), never expressions, so evaluating a rule can never run code.
 *
 * <p>Hit policies: FIRST (the first matching rule), UNIQUE (at most one rule may match; two is a rule error), COLLECT
 * (every matching rule, in order). With no match the decision has no output, unless the table names default outputs.
 */
public record DecisionTable(List<Column> inputs, List<Column> outputs, HitPolicy hitPolicy, List<Rule> rules,
                           Map<String, Object> defaults) {

    public enum Type { NUMBER, TEXT, BOOLEAN, DATE }

    public enum HitPolicy { FIRST, UNIQUE, COLLECT }

    public enum Op { ANY, EQ, NE, LT, LE, GT, GE, BETWEEN, IN, NOT_IN }

    private static final Pattern NAME = Pattern.compile("^[a-z][A-Za-z0-9]{0,39}$");
    public static final int MAX_RULES = 500;

    /** A named, typed input or output. */
    public record Column(String name, Type type) {
        public Column {
            if (name == null || !NAME.matcher(name).matches()) {
                throw invalid("column names are camelCase, 1-40 characters: " + name);
            }
            Objects.requireNonNull(type, "type");
        }
    }

    /** One condition on one input: {@code op} with {@code values} (one value, two for BETWEEN, a list for IN). */
    public record Condition(Op op, List<Object> values) {
        public static final Condition ANY = new Condition(Op.ANY, List.of());

        public Condition {
            Objects.requireNonNull(op, "op");
            values = values == null ? List.of() : List.copyOf(values);
            int n = values.size();
            boolean ok = switch (op) {
                case ANY -> n == 0;
                case BETWEEN -> n == 2;
                case IN, NOT_IN -> n >= 1 && n <= 100;
                default -> n == 1;
            };
            if (!ok) {
                throw invalid("operator " + op + " has the wrong number of values");
            }
        }

        boolean test(Object actual, Type type) {
            if (op == Op.ANY) {
                return true;
            }
            if (actual == null) {
                return false;
            }
            return switch (op) {
                case EQ -> compare(actual, values.getFirst(), type) == 0;
                case NE -> compare(actual, values.getFirst(), type) != 0;
                case LT -> compare(actual, values.getFirst(), type) < 0;
                case LE -> compare(actual, values.getFirst(), type) <= 0;
                case GT -> compare(actual, values.getFirst(), type) > 0;
                case GE -> compare(actual, values.getFirst(), type) >= 0;
                case BETWEEN -> compare(actual, values.get(0), type) >= 0 && compare(actual, values.get(1), type) <= 0;
                case IN -> values.stream().anyMatch(v -> compare(actual, v, type) == 0);
                case NOT_IN -> values.stream().noneMatch(v -> compare(actual, v, type) == 0);
                case ANY -> true;
            };
        }
    }

    /** One row: a condition per input (missing = ANY) and a value per output, with an optional description. */
    public record Rule(Map<String, Condition> when, Map<String, Object> then, String description) {
        public Rule {
            when = when == null ? Map.of() : Map.copyOf(when);
            then = then == null ? Map.of() : Map.copyOf(then);
            if (description != null && (description.length() > 200
                    || description.chars().anyMatch(Character::isISOControl))) {
                throw invalid("rule description too long or malformed");
            }
        }
    }

    public DecisionTable {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        Objects.requireNonNull(hitPolicy, "hitPolicy");
        rules = List.copyOf(rules);
        defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
        if (inputs.isEmpty() || inputs.size() > 30 || outputs.isEmpty() || outputs.size() > 30) {
            throw invalid("a table has 1-30 inputs and 1-30 outputs");
        }
        Set<String> names = new HashSet<>();
        for (Column c : inputs) {
            if (!names.add(c.name())) {
                throw invalid("column " + c.name() + " appears twice");
            }
        }
        for (Column c : outputs) {
            if (!names.add(c.name())) {
                throw invalid("column " + c.name() + " appears twice");
            }
        }
        if (rules.isEmpty() || rules.size() > MAX_RULES) {
            throw invalid("a table has 1-" + MAX_RULES + " rules");
        }
        Map<String, Type> inTypes = types(inputs);
        Map<String, Type> outTypes = types(outputs);
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            String row = "rule " + (i + 1);
            for (Map.Entry<String, Condition> c : r.when().entrySet()) {
                Type t = inTypes.get(c.getKey());
                if (t == null) {
                    throw invalid(row + " tests an unknown input " + c.getKey());
                }
                c.getValue().values().forEach(v -> checkValue(v, t, row + " input " + c.getKey()));
                if (t == Type.BOOLEAN && !Set.of(Op.ANY, Op.EQ, Op.NE).contains(c.getValue().op())) {
                    throw invalid("rule " + (i + 1) + " compares a boolean with " + c.getValue().op());
                }
            }
            checkOutputs(r.then(), outTypes, "rule " + (i + 1));
        }
        checkOutputs(defaults, outTypes, "defaults");
    }

    private static void checkOutputs(Map<String, Object> values, Map<String, Type> outTypes, String where) {
        for (Map.Entry<String, Object> o : values.entrySet()) {
            Type t = outTypes.get(o.getKey());
            if (t == null) {
                throw invalid(where + " sets an unknown output " + o.getKey());
            }
            checkValue(o.getValue(), t, where + " output " + o.getKey());
        }
    }

    /** Checks that the inputs of a request have the declared types; unknown inputs are refused. */
    public Map<String, Object> checkInputs(Map<String, Object> given) {
        Map<String, Type> inTypes = types(inputs);
        Map<String, Object> checked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : given.entrySet()) {
            Type t = inTypes.get(e.getKey());
            if (t == null) {
                throw DomainException.validation("RULE_INPUT_UNKNOWN", "the rule has no input " + e.getKey(),
                        List.of("CC-050"), e.getKey());
            }
            if (e.getValue() != null) {
                try {
                    checkValue(e.getValue(), t, "input " + e.getKey());
                } catch (DomainException ex) {
                    throw DomainException.validation("RULE_INPUT_INVALID",
                            "input " + e.getKey() + " must be a " + t, List.of("CC-050"), e.getKey(), t.name());
                }
            }
            checked.put(e.getKey(), normalise(e.getValue(), t));
        }
        return checked;
    }

    /** The rule rows that match, as indexes (0-based) in table order. */
    public List<Integer> matches(Map<String, Object> given) {
        Map<String, Type> inTypes = types(inputs);
        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            boolean all = true;
            for (Column c : inputs) {
                Condition cond = rules.get(i).when().getOrDefault(c.name(), Condition.ANY);
                if (!cond.test(normalise(given.get(c.name()), inTypes.get(c.name())), c.type())) {
                    all = false;
                    break;
                }
            }
            if (all) {
                matched.add(i);
                if (hitPolicy == HitPolicy.FIRST) {
                    break;
                }
            }
        }
        if (hitPolicy == HitPolicy.UNIQUE && matched.size() > 1) {
            throw DomainException.conflict("RULE_NOT_UNIQUE",
                    "rules " + matched.stream().map(i -> String.valueOf(i + 1)).toList() + " all match (UNIQUE)",
                    List.of("CC-050"));
        }
        return matched;
    }

    /** The outputs of the matched rules, or the defaults when nothing matched. */
    public List<Map<String, Object>> outputsOf(List<Integer> matched) {
        if (matched.isEmpty()) {
            return defaults.isEmpty() ? List.of() : List.of(defaults);
        }
        return matched.stream().map(i -> rules.get(i).then()).toList();
    }

    private static Map<String, Type> types(List<Column> columns) {
        Map<String, Type> m = new LinkedHashMap<>();
        columns.forEach(c -> m.put(c.name(), c.type()));
        return m;
    }

    private static void checkValue(Object v, Type t, String where) {
        boolean ok = switch (t) {
            case NUMBER -> v instanceof Number;
            case TEXT -> v instanceof String s && s.length() <= 200;
            case BOOLEAN -> v instanceof Boolean;
            case DATE -> v instanceof String s && s.matches("^[0-9]{4}-[0-9]{2}-[0-9]{2}$") && parses(s);
        };
        if (!ok) {
            throw invalid(where + " must be a " + t);
        }
    }

    private static boolean parses(String date) {
        try {
            LocalDate.parse(date);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Object normalise(Object v, Type t) {
        if (v == null) {
            return null;
        }
        return switch (t) {
            case NUMBER -> new BigDecimal(v.toString());
            case DATE -> LocalDate.parse(v.toString());
            default -> v;
        };
    }

    @SuppressWarnings("unchecked")
    private static int compare(Object actual, Object expected, Type type) {
        Object e = normalise(expected, type);
        Object a = normalise(actual, type);
        if (type == Type.BOOLEAN) {
            return a.equals(e) ? 0 : 1;
        }
        return ((Comparable<Object>) a).compareTo(e);
    }

    static DomainException invalid(String detail) {
        return DomainException.validation("RULE_TABLE_INVALID", "the rule table is invalid: " + detail,
                List.of("CC-050"), detail);
    }
}

package gov.itms.fees.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A fee formula (CC-041 "or a formula"), kept small and safe on purpose: decimal numbers, variables (quantity units in
 * lower case, for example {@code seats}, {@code tonne}), {@code + - * /}, parentheses and the functions
 * {@code min(a, b, ...)}, {@code max(a, b, ...)}, {@code round(x)}, {@code ceil(x)}, {@code floor(x)}. No scripting
 * engine is involved, so a formula cannot run code. The result is in minor units, rounded half up.
 *
 * <p>Grammar: {@code expr := term (('+'|'-') term)*; term := factor (('*'|'/') factor)*;
 * factor := number | name | name '(' expr (',' expr)* ')' | '(' expr ')' | '-' factor}.
 */
public final class FeeFormula {

    public static final int MAX_LENGTH = 500;
    private static final Set<String> FUNCTIONS = Set.of("min", "max", "round", "ceil", "floor");
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_UP);

    private final String expression;
    private final Node root;

    private FeeFormula(String expression, Node root) {
        this.expression = expression;
        this.root = root;
    }

    /** Parses and checks a formula; a syntax error is a validation error naming the position. */
    public static FeeFormula parse(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > MAX_LENGTH) {
            throw invalid("the formula is empty or longer than " + MAX_LENGTH + " characters");
        }
        Parser parser = new Parser(expression);
        Node root = parser.expression();
        parser.skipSpaces();
        if (parser.pos != expression.length()) {
            throw invalid("unexpected '" + expression.charAt(parser.pos) + "' at position " + (parser.pos + 1));
        }
        return new FeeFormula(expression.strip(), root);
    }

    public String expression() {
        return expression;
    }

    /** The variables the formula needs, sorted. */
    public Set<String> variables() {
        Set<String> names = new TreeSet<>();
        root.collect(names);
        return names;
    }

    /** Evaluates with the given variables; a missing variable or a division by zero is a validation error. */
    public BigDecimal evaluate(Map<String, BigDecimal> variables) {
        return root.eval(variables);
    }

    private static DomainException invalid(String detail) {
        return DomainException.validation("FORMULA_INVALID", "the fee formula is invalid: " + detail,
                List.of("CC-041"), detail);
    }

    private sealed interface Node permits Num, Var, Neg, Bin, Fn {
        BigDecimal eval(Map<String, BigDecimal> v);

        default void collect(Set<String> names) {
        }
    }

    private record Num(BigDecimal value) implements Node {
        public BigDecimal eval(Map<String, BigDecimal> v) {
            return value;
        }
    }

    private record Var(String name) implements Node {
        public BigDecimal eval(Map<String, BigDecimal> v) {
            BigDecimal value = v.get(name);
            if (value == null) {
                throw DomainException.validation("QUANTITY_REQUIRED", "the quote needs the quantity " + name,
                        List.of("CC-041"), name);
            }
            return value;
        }

        public void collect(Set<String> names) {
            names.add(name);
        }
    }

    private record Neg(Node inner) implements Node {
        public BigDecimal eval(Map<String, BigDecimal> v) {
            return inner.eval(v).negate();
        }

        public void collect(Set<String> names) {
            inner.collect(names);
        }
    }

    private record Bin(char op, Node left, Node right) implements Node {
        public BigDecimal eval(Map<String, BigDecimal> v) {
            BigDecimal a = left.eval(v);
            BigDecimal b = right.eval(v);
            return switch (op) {
                case '+' -> a.add(b, MC);
                case '-' -> a.subtract(b, MC);
                case '*' -> a.multiply(b, MC);
                default -> {
                    if (b.signum() == 0) {
                        throw DomainException.validation("FORMULA_DIVISION_BY_ZERO",
                                "the fee formula divides by zero for these quantities", List.of("CC-041"));
                    }
                    yield a.divide(b, MC);
                }
            };
        }

        public void collect(Set<String> names) {
            left.collect(names);
            right.collect(names);
        }
    }

    private record Fn(String name, List<Node> args) implements Node {
        public BigDecimal eval(Map<String, BigDecimal> v) {
            List<BigDecimal> values = args.stream().map(a -> a.eval(v)).toList();
            return switch (name) {
                case "min" -> values.stream().min(BigDecimal::compareTo).orElseThrow();
                case "max" -> values.stream().max(BigDecimal::compareTo).orElseThrow();
                case "round" -> values.getFirst().setScale(0, RoundingMode.HALF_UP);
                case "ceil" -> values.getFirst().setScale(0, RoundingMode.CEILING);
                default -> values.getFirst().setScale(0, RoundingMode.FLOOR);
            };
        }

        public void collect(Set<String> names) {
            args.forEach(a -> a.collect(names));
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipSpaces() {
            while (pos < s.length() && s.charAt(pos) == ' ') {
                pos++;
            }
        }

        boolean accept(char c) {
            skipSpaces();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        Node expression() {
            Node node = term();
            while (true) {
                if (accept('+')) {
                    node = new Bin('+', node, term());
                } else if (accept('-')) {
                    node = new Bin('-', node, term());
                } else {
                    return node;
                }
            }
        }

        Node term() {
            Node node = factor();
            while (true) {
                if (accept('*')) {
                    node = new Bin('*', node, factor());
                } else if (accept('/')) {
                    node = new Bin('/', node, factor());
                } else {
                    return node;
                }
            }
        }

        Node factor() {
            skipSpaces();
            if (pos >= s.length()) {
                throw invalid("the formula ends too early");
            }
            char c = s.charAt(pos);
            if (accept('-')) {
                return new Neg(factor());
            }
            if (accept('(')) {
                Node inner = expression();
                if (!accept(')')) {
                    throw invalid("a ')' is missing at position " + (pos + 1));
                }
                return inner;
            }
            if (Character.isDigit(c)) {
                int start = pos;
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                    pos++;
                }
                String text = s.substring(start, pos);
                if (!text.matches("^[0-9]{1,15}(\\.[0-9]{1,6})?$")) {
                    throw invalid("bad number '" + text + "'");
                }
                return new Num(new BigDecimal(text));
            }
            if (c >= 'a' && c <= 'z') {
                int start = pos;
                while (pos < s.length() && ((s.charAt(pos) >= 'a' && s.charAt(pos) <= 'z')
                        || Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                    pos++;
                }
                String name = s.substring(start, pos);
                if (name.length() > 40) {
                    throw invalid("name too long at position " + (start + 1));
                }
                if (accept('(')) {
                    if (!FUNCTIONS.contains(name)) {
                        throw invalid("unknown function '" + name + "'");
                    }
                    List<Node> args = new ArrayList<>();
                    args.add(expression());
                    while (accept(',')) {
                        args.add(expression());
                    }
                    if (!accept(')')) {
                        throw invalid("a ')' is missing at position " + (pos + 1));
                    }
                    boolean single = !name.equals("min") && !name.equals("max");
                    if (single && args.size() != 1 || args.size() > 20) {
                        throw invalid("wrong number of arguments for " + name);
                    }
                    return new Fn(name, List.copyOf(args));
                }
                if (FUNCTIONS.contains(name)) {
                    throw invalid("function '" + name + "' needs parentheses");
                }
                return new Var(name);
            }
            throw invalid("unexpected '" + c + "' at position " + (pos + 1));
        }
    }
}

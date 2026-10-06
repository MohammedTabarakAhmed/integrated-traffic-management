package gov.itms.fees.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * How a fee line is calculated (CC-041): fixed, per unit (per year, month, tonne, seat...), tiered, a formula, or a
 * late fee counted from an expiry date (CC-044).
 * Quantities come with the quote, keyed by unit code (for example {@code YEAR}, {@code SEAT}); a formula sees them in
 * lower case. Amounts are minor units; results are rounded half up to a whole minor unit.
 */
public sealed interface FeeMethod {

    /** What a method may depend on: quantities by unit, the subject's expiry date (if any) and the quote date. */
    record Inputs(Map<String, BigDecimal> quantities, LocalDate expiryDate, LocalDate onDate) {
        public Inputs {
            quantities = Map.copyOf(quantities);
            Objects.requireNonNull(onDate, "onDate");
        }

        public static Inputs of(Map<String, BigDecimal> quantities) {
            return new Inputs(quantities, null, LocalDate.of(2000, 1, 1));
        }
    }

    /** The amount for these inputs, in minor units. */
    long amount(Inputs inputs);

    /** The quantity units the method needs (for the quote's input check and the API). */
    List<String> units();

    record Fixed(long amount) implements FeeMethod {
        public Fixed {
            Codes.amount("amount", amount);
        }

        @Override
        public long amount(Inputs inputs) {
            return amount;
        }

        @Override
        public List<String> units() {
            return List.of();
        }
    }

    /** {@code rate} minor units per unit, optionally with a minimum and maximum charge. */
    record PerUnit(String unit, BigDecimal rate, Long minimum, Long maximum) implements FeeMethod {
        public PerUnit {
            unit = Codes.code("unit", unit);
            rate = Codes.decimal("rate", rate);
            if (minimum != null) {
                Codes.amount("minimum", minimum);
            }
            if (maximum != null) {
                Codes.amount("maximum", maximum);
            }
            if (minimum != null && maximum != null && minimum > maximum) {
                throw Codes.invalid("minimum");
            }
        }

        @Override
        public long amount(Inputs inputs) {
            long value = round(rate.multiply(quantity(inputs.quantities(), unit)));
            if (minimum != null) {
                value = Math.max(value, minimum);
            }
            if (maximum != null) {
                value = Math.min(value, maximum);
            }
            return value;
        }

        @Override
        public List<String> units() {
            return List.of(unit);
        }
    }

    /**
     * Tiers by quantity of {@code unit}. GRADUATED charges each band at its own rate (like income tax bands); VOLUME
     * charges the whole quantity at the rate of the band it falls in. Bands are given by their upper bound
     * (inclusive), increasing; the last has no upper bound.
     */
    record Tiered(String unit, Mode mode, List<Tier> tiers) implements FeeMethod {

        public enum Mode { GRADUATED, VOLUME }

        public record Tier(BigDecimal upTo, BigDecimal rate) {
            public Tier {
                if (upTo != null) {
                    upTo = Codes.decimal("upTo", upTo);
                }
                rate = Codes.decimal("rate", rate);
            }
        }

        public Tiered {
            unit = Codes.code("unit", unit);
            Objects.requireNonNull(mode, "mode");
            if (tiers == null || tiers.isEmpty() || tiers.size() > 20) {
                throw Codes.invalid("tiers");
            }
            tiers = List.copyOf(tiers);
            for (int i = 0; i < tiers.size(); i++) {
                Tier t = tiers.get(i);
                boolean last = i == tiers.size() - 1;
                if (last != (t.upTo() == null)) {
                    throw DomainException.validation("TIERS_INVALID",
                            "only the last tier is open-ended", List.of("CC-041"));
                }
                if (i > 0 && !last && t.upTo().compareTo(tiers.get(i - 1).upTo()) <= 0) {
                    throw DomainException.validation("TIERS_INVALID", "tier bounds must increase",
                            List.of("CC-041"));
                }
            }
        }

        @Override
        public long amount(Inputs inputs) {
            BigDecimal q = quantity(inputs.quantities(), unit);
            if (mode == Mode.VOLUME) {
                Tier band = tiers.stream().filter(t -> t.upTo() == null || q.compareTo(t.upTo()) <= 0).findFirst()
                        .orElseThrow();
                return round(band.rate().multiply(q));
            }
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal lower = BigDecimal.ZERO;
            for (Tier t : tiers) {
                if (q.compareTo(lower) <= 0) {
                    break;
                }
                BigDecimal upper = t.upTo() == null ? q : q.min(t.upTo());
                total = total.add(upper.subtract(lower).multiply(t.rate()));
                if (t.upTo() == null) {
                    break;
                }
                lower = t.upTo();
            }
            return round(total);
        }

        @Override
        public List<String> units() {
            return List.of(unit);
        }
    }

    /** A {@link FeeFormula}; variables are quantity units in lower case. */
    record Formula(String expression) implements FeeMethod {
        public Formula {
            expression = FeeFormula.parse(expression).expression();
        }

        @Override
        public long amount(Inputs inputs) {
            Map<String, BigDecimal> variables = new java.util.HashMap<>();
            inputs.quantities().forEach((k, v) -> variables.put(k.toLowerCase(Locale.ROOT), v));
            BigDecimal result = FeeFormula.parse(expression).evaluate(variables);
            if (result.signum() < 0) {
                throw DomainException.validation("FORMULA_NEGATIVE", "the fee formula gives a negative amount",
                        List.of("CC-041"));
            }
            return round(result);
        }

        @Override
        public List<String> units() {
            List<String> units = new ArrayList<>();
            FeeFormula.parse(expression).variables().forEach(v -> units.add(v.toUpperCase(Locale.ROOT)));
            return List.copyOf(units);
        }
    }

    /**
     * A late fee (CC-044): {@code rate} minor units for every started {@code period} (DAY, MONTH or YEAR) that the
     * quote date lies after the expiry date plus {@code graceDays}, capped at {@code maximum} if set. Nothing is due
     * while not late. The quote must give the expiry date.
     */
    record Late(Period period, long rate, int graceDays, Long maximum) implements FeeMethod {

        public enum Period { DAY, MONTH, YEAR }

        public Late {
            Objects.requireNonNull(period, "period");
            Codes.amount("rate", rate);
            if (graceDays < 0 || graceDays > 3650) {
                throw Codes.invalid("graceDays");
            }
            if (maximum != null) {
                Codes.amount("maximum", maximum);
            }
        }

        @Override
        public long amount(Inputs inputs) {
            if (inputs.expiryDate() == null) {
                throw DomainException.validation("EXPIRY_DATE_REQUIRED",
                        "the quote needs the expiry date to calculate the late fee", List.of("CC-044"));
            }
            LocalDate lateFrom = inputs.expiryDate().plusDays(graceDays);
            if (!inputs.onDate().isAfter(lateFrom)) {
                return 0;
            }
            long started = switch (period) {
                case DAY -> ChronoUnit.DAYS.between(lateFrom, inputs.onDate());
                case MONTH -> startedPeriods(lateFrom, inputs.onDate(), ChronoUnit.MONTHS);
                case YEAR -> startedPeriods(lateFrom, inputs.onDate(), ChronoUnit.YEARS);
            };
            BigDecimal value = BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(started));
            long amount = round(value);
            return maximum == null ? amount : Math.min(amount, maximum);
        }

        private static long startedPeriods(LocalDate from, LocalDate to, ChronoUnit unit) {
            long whole = unit.between(from, to);
            return from.plus(whole, unit).isBefore(to) ? whole + 1 : whole;
        }

        @Override
        public List<String> units() {
            return List.of();
        }
    }

    private static BigDecimal quantity(Map<String, BigDecimal> quantities, String unit) {
        BigDecimal q = quantities.get(unit);
        if (q == null) {
            throw DomainException.validation("QUANTITY_REQUIRED", "the quote needs the quantity " + unit,
                    List.of("CC-041"), unit);
        }
        return q;
    }

    private static long round(BigDecimal value) {
        BigDecimal rounded = value.setScale(0, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.valueOf(Codes.MAX_AMOUNT)) > 0) {
            throw DomainException.validation("AMOUNT_TOO_LARGE", "the fee is larger than the system allows",
                    List.of("CC-041"));
        }
        return rounded.longValueExact();
    }
}

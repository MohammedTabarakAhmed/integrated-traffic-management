package gov.itms.fees.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Formats of codes and amounts. Fee, unit, financial item, beneficiary, region and category codes are reference-data
 * codes (CC-110), checked for their format until the reference-data client exists (Q-044, Q-062). Service codes follow
 * the catalogue (ADR-028). Amounts are integer minor units of the configured currency (Q-006); rates may carry up to
 * four decimals of a minor unit.
 */
public final class Codes {

    public static final String CODE = "^[A-Z0-9_]{1,40}$";
    public static final String SERVICE_CODE = "^[A-Z][A-Z0-9_]{1,19}$";
    public static final String DECIMAL = "^[0-9]{1,12}(\\.[0-9]{1,4})?$";
    public static final long MAX_AMOUNT = 1_000_000_000_000L;

    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);
    private static final Pattern SERVICE_PATTERN = Pattern.compile(SERVICE_CODE);

    private Codes() {
    }

    public static String code(String field, String value) {
        if (value == null || !CODE_PATTERN.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }

    public static String optionalCode(String field, String value) {
        return value == null ? null : code(field, value);
    }

    public static String serviceCode(String value) {
        if (value == null || !SERVICE_PATTERN.matcher(value).matches()) {
            throw invalid("serviceCode");
        }
        return value;
    }

    /** A non-negative amount in minor units, at most {@link #MAX_AMOUNT}. */
    public static long amount(String field, long value) {
        if (value < 0 || value > MAX_AMOUNT) {
            throw invalid(field);
        }
        return value;
    }

    /** A non-negative decimal with at most four fraction digits and at most {@link #MAX_AMOUNT}. */
    public static BigDecimal decimal(String field, BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 4
                || value.compareTo(BigDecimal.valueOf(MAX_AMOUNT)) > 0) {
            throw invalid(field);
        }
        return value.stripTrailingZeros();
    }

    public static String name(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw invalid(field);
            }
            return null;
        }
        String v = value.strip();
        if (v.length() > 200 || v.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field);
        }
        return v;
    }

    public static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("CC-040"),
                field);
    }
}

package gov.itms.transaction.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Identifier formats used by transaction-service (DAT-004). */
public final class Codes {

    /** Reference data codes: modules, customer types, documents, regions, fee/rule/workflow references. */
    public static final String CODE = "^[A-Z0-9_]{1,40}$";
    /** Service codes are part of every transaction reference {@code <ServiceCode>-<Region>-<YYYY>-<Seq>} (CC-021). */
    public static final String SERVICE_CODE = "^[A-Z][A-Z0-9_]{1,19}$";
    public static final String ALL = "*";

    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);
    private static final Pattern SERVICE_PATTERN = Pattern.compile(SERVICE_CODE);

    private Codes() {
    }

    public static String code(String field, String value) {
        if (value == null || !CODE_PATTERN.matcher(value).matches()) {
            throw DomainException.validation("INVALID_CODE", field + " must be 1-40 characters of A-Z, 0-9 or _",
                    field);
        }
        return value;
    }

    public static String optionalCode(String field, String value) {
        return value == null ? null : code(field, value);
    }

    public static String serviceCode(String value) {
        if (value == null || !SERVICE_PATTERN.matcher(value).matches()) {
            throw DomainException.validation("INVALID_SERVICE_CODE",
                    "a service code must be 2-20 characters of A-Z, 0-9 or _, starting with a letter");
        }
        return value;
    }

    /** A region code, or {@code *} for every region. */
    public static String regionOrAll(String field, String value) {
        return ALL.equals(value) ? ALL : code(field, value);
    }

    /** A list of distinct codes, between {@code min} and {@code max} entries, kept in the order given. */
    public static List<String> codeList(String field, List<String> values, int min, int max) {
        List<String> list = distinct(field, values, min, max);
        list.forEach(v -> code(field, v));
        return list;
    }

    public static <T> List<T> distinct(String field, List<T> values, int min, int max) {
        if (values == null || values.size() < min || values.size() > max) {
            throw DomainException.validation("LIST_SIZE_INVALID",
                    field + " needs " + min + " to " + max + " entries", field, min, max);
        }
        // stream check: List.of(...).contains(null) throws instead of answering false
        if (values.stream().anyMatch(Objects::isNull) || new HashSet<>(values).size() != values.size()) {
            throw DomainException.validation("LIST_DUPLICATES", field + " must not repeat an entry", field);
        }
        return List.copyOf(values);
    }

    /** Reasons: at least 3 characters (code points, as the database counts) and at most 500. */
    public static String reason(String value) {
        String clean = value == null ? "" : value.strip();
        if (clean.codePointCount(0, clean.length()) < 3) {
            throw DomainException.validation("REASON_REQUIRED", "a reason of at least 3 characters is required");
        }
        if (clean.length() > 500) {
            throw DomainException.validation("REASON_TOO_LONG", "the reason can be at most 500 characters");
        }
        return clean;
    }
}

package gov.itms.hold.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Identifier formats used by hold-eligibility-service (DAT-004); the same formats as the owning services. */
public final class Codes {

    /** Reference data codes: hold types (CC-031), modules, sources. */
    public static final String CODE = "^[A-Z0-9_]{1,40}$";
    /** Service codes of transaction-service's catalogue (CC-020). */
    public static final String SERVICE_CODE = "^[A-Z][A-Z0-9_]{1,19}$";
    /** Role codes of iam-service (CC-001). */
    public static final String ROLE_CODE = "^[A-Z][A-Z0-9_]{1,59}$";
    /** An external agency's own reference for the hold (CC-033). */
    public static final String SOURCE_REF = "^[A-Za-z0-9._:/-]{1,100}$";
    public static final String ALL = "*";

    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);
    private static final Pattern SERVICE_PATTERN = Pattern.compile(SERVICE_CODE);
    private static final Pattern ROLE_PATTERN = Pattern.compile(ROLE_CODE);
    private static final Pattern SOURCE_REF_PATTERN = Pattern.compile(SOURCE_REF);

    private Codes() {
    }

    public static String code(String field, String value) {
        if (value == null || !CODE_PATTERN.matcher(value).matches()) {
            throw DomainException.validation("INVALID_CODE", field + " must be 1-40 characters of A-Z, 0-9 or _",
                    field);
        }
        return value;
    }

    public static String sourceRef(String value) {
        if (value != null && !SOURCE_REF_PATTERN.matcher(value).matches()) {
            throw DomainException.validation("INVALID_SOURCE_REF",
                    "sourceRef must be 1-100 characters of letters, digits, '.', '_', ':', '/' or '-'");
        }
        return value;
    }

    /** {@code ["*"]} for every service, or 1-100 distinct service codes (CC-030). */
    public static List<String> blockedServices(List<String> values) {
        List<String> list = distinct("blockedServices", values, 1, 100);
        if (list.contains(ALL)) {
            if (list.size() > 1) {
                throw DomainException.validation("SERVICES_ALL_MIXED",
                        "choose either every service (*) or a list of services, not both");
            }
            return list;
        }
        for (String code : list) {
            if (!SERVICE_PATTERN.matcher(code).matches()) {
                throw DomainException.validation("INVALID_SERVICE_CODE",
                        "a service code must be 2-20 characters of A-Z, 0-9 or _, starting with a letter");
            }
        }
        return list;
    }

    /** 1-20 distinct role codes (CC-030 "who may lift it"). */
    public static List<String> roles(List<String> values) {
        List<String> list = distinct("liftableByRoles", values, 1, 20);
        for (String code : list) {
            if (!ROLE_PATTERN.matcher(code).matches()) {
                throw DomainException.validation("INVALID_ROLE_CODE",
                        "a role code must be 2-60 characters of A-Z, 0-9 or _, starting with a letter");
            }
        }
        return list;
    }

    static <T> List<T> distinct(String field, List<T> values, int min, int max) {
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

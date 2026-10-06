package gov.itms.trafficfile.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Formats of codes and identifiers. Codes are reference-data codes (CC-110); until the reference-data client exists
 * they are checked for their format only (Q-044, Q-057). The national identity number format is configuration
 * ({@code itms.traffic-file.national-id-pattern}), because the BRD does not fix it (Q-057).
 */
public final class Codes {

    public static final String CODE = "^[A-Z0-9_]{1,40}$";
    public static final String COUNTRY = "^[A-Z]{3}$";
    public static final String PASSPORT = "^[A-Z0-9]{5,20}$";
    public static final String FILE_NUMBER = "^[A-Z0-9][A-Z0-9-]{0,19}$";
    public static final String MOBILE = "^\\+[1-9][0-9]{6,14}$";
    public static final String EMAIL = "^[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s]{2,}$";

    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);
    private static final Pattern COUNTRY_PATTERN = Pattern.compile(COUNTRY);
    private static final Pattern PASSPORT_PATTERN = Pattern.compile(PASSPORT);
    private static final Pattern MOBILE_PATTERN = Pattern.compile(MOBILE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile(EMAIL);

    private Codes() {
    }

    public static String code(String field, String value) {
        return matching(field, value, CODE_PATTERN);
    }

    public static String optionalCode(String field, String value) {
        return value == null ? null : code(field, value);
    }

    public static String country(String field, String value) {
        return matching(field, value, COUNTRY_PATTERN);
    }

    public static String passport(String value) {
        return matching("passportNo", value, PASSPORT_PATTERN);
    }

    public static String optionalMobile(String value) {
        return value == null ? null : matching("mobile", value, MOBILE_PATTERN);
    }

    public static String optionalEmail(String value) {
        return value == null ? null : matching("email", value, EMAIL_PATTERN);
    }

    public static List<String> codes(String field, List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > 20) {
            throw invalid(field);
        }
        if (values.stream().distinct().count() != values.size()) {
            throw DomainException.validation("LIST_DUPLICATES", field + " repeats a value", List.of("DL-005"), field);
        }
        return values.stream().map(v -> code(field, v)).toList();
    }

    private static String matching(String field, String value, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }

    private static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("DL-005"),
                field);
    }
}

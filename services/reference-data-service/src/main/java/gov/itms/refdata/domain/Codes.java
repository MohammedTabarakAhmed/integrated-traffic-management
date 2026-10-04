package gov.itms.refdata.domain;

import java.util.regex.Pattern;

/** Code format shared by list codes, item codes, region codes and partner codes (DAT-004). */
public final class Codes {

    public static final String PATTERN = "^[A-Z0-9_]{1,40}$";
    private static final Pattern CODE = Pattern.compile(PATTERN);

    private Codes() {
    }

    public static String require(String field, String value) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw DomainException.validation("INVALID_CODE",
                    field + " must be 1-40 characters of A-Z, 0-9 or _", field);
        }
        return value;
    }

    public static String optional(String field, String value) {
        return value == null ? null : require(field, value);
    }
}

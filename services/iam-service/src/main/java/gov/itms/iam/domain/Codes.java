package gov.itms.iam.domain;

import java.util.regex.Pattern;

/** Identifier formats used by iam-service (DAT-004). */
public final class Codes {

    public static final String ROLE_CODE = "^[A-Z][A-Z0-9_]{1,59}$";
    public static final String PERMISSION_CODE = "^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*){1,4}$";
    public static final String SUBJECT_ID = "^[A-Za-z0-9._@:-]{1,100}$";
    public static final String SCOPE_VALUE = "^(\\*|[A-Z0-9_]{1,40})$";

    private static final Pattern ROLE = Pattern.compile(ROLE_CODE);
    private static final Pattern PERMISSION = Pattern.compile(PERMISSION_CODE);
    private static final Pattern SUBJECT = Pattern.compile(SUBJECT_ID);

    private Codes() {
    }

    public static String roleCode(String value) {
        if (value == null || !ROLE.matcher(value).matches()) {
            throw DomainException.validation("INVALID_ROLE_CODE",
                    "role code must be 2-60 characters of A-Z, 0-9 or _, starting with a letter");
        }
        return value;
    }

    public static String permissionCode(String value) {
        if (value == null || !PERMISSION.matcher(value).matches()) {
            throw DomainException.validation("INVALID_PERMISSION_CODE",
                    "permission " + value + " is not a valid permission code", String.valueOf(value));
        }
        return value;
    }

    public static String subjectId(String value) {
        if (value == null || !SUBJECT.matcher(value).matches()) {
            throw DomainException.validation("INVALID_SUBJECT", "subject id is missing or invalid");
        }
        return value;
    }

    public static String reason(String value) {
        // At least 3 characters counted as code points, as PostgreSQL char_length does (V5 checks).
        if (value == null || value.strip().codePointCount(0, value.strip().length()) < 3) {
            throw DomainException.validation("REASON_REQUIRED", "a reason of at least 3 characters is required");
        }
        String clean = value.strip();
        if (clean.length() > 500) {
            throw DomainException.validation("REASON_TOO_LONG", "the reason can be at most 500 characters");
        }
        return clean;
    }
}

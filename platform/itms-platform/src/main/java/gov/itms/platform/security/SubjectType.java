package gov.itms.platform.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Customer or staff, from the token (ADR-067). Only the customer portal client puts {@code subject_type=CUSTOMER}
 * in a token, and the claim can only narrow access: iam-service then ignores role assignments and grants the
 * customer role alone.
 */
public final class SubjectType {

    public static final String CLAIM = "subject_type";
    public static final String CUSTOMER = "CUSTOMER";
    /** The national identity number of a customer (ADR-067); staff tokens do not carry it. */
    public static final String NATIONAL_ID_CLAIM = "national_id";

    private SubjectType() {
    }

    public static boolean isCustomer(Jwt jwt) {
        return CUSTOMER.equals(jwt.getClaimAsString(CLAIM));
    }
}

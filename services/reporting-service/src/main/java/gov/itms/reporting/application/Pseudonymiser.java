package gov.itms.reporting.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pseudonymises personal identifiers before they reach the warehouse (RPT-022): a keyed HMAC-SHA256, so the same
 * person has the same pseudonym across facts (counts per person stay possible) while the identifier cannot be read
 * back or recomputed without the key, which is a secret file. Fields are named in configuration.
 */
public final class Pseudonymiser {

    private final byte[] key;
    private final Set<String> fields;

    public Pseudonymiser(byte[] key, Set<String> fields) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("the pseudonymisation key must have at least 32 bytes (RPT-022)");
        }
        this.key = key.clone();
        this.fields = Set.copyOf(fields);
    }

    public boolean personal(String field) {
        return fields.contains(field);
    }

    /** {@code P-} and 24 hex digits of HMAC-SHA256(key, value). */
    public String pseudonym(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "P-" + HexFormat.of().formatHex(h, 0, 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}

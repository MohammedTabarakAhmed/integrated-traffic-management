package gov.itms.document.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Certificate templates and issued certificates (CC-082, CC-083; ADR-051). */
public final class Certificate {

    private Certificate() {
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]{0,39})}}");
    private static final Pattern CODE = Pattern.compile(Document.CODE);

    /**
     * A bilingual certificate or letter template, for example a no-objection, clearance or ownership certificate.
     * Text with {@code {{placeholder}}} fields; validity in days ({@code null} = no end). Versions are immutable.
     */
    public record Template(String code, int versionNo, String titleNl, String titleEn, String bodyNl, String bodyEn,
                           Integer validityDays, Actor publishedBy, Instant publishedAt) {
        public Template {
            if (code == null || !CODE.matcher(code).matches()) {
                throw Document.invalid("code");
            }
            text("titleNl", titleNl, 200);
            text("titleEn", titleEn, 200);
            text("bodyNl", bodyNl, 20000);
            text("bodyEn", bodyEn, 20000);
            if (validityDays != null && (validityDays < 1 || validityDays > 3650)) {
                throw DomainException.validation("VALIDITY_INVALID", "validity is 1 to 3650 days", List.of("CC-082"));
            }
            Objects.requireNonNull(publishedBy);
            Objects.requireNonNull(publishedAt);
        }

        /** The text with every placeholder filled; a placeholder without a value is refused. */
        public String fill(String text, Map<String, String> data) {
            Matcher m = PLACEHOLDER.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String value = data.get(m.group(1));
                if (value == null) {
                    throw DomainException.validation("CERTIFICATE_DATA_MISSING",
                            "template " + code + " needs the value " + m.group(1), List.of("CC-082"), m.group(1));
                }
                m.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            m.appendTail(out);
            return out.toString();
        }
    }

    public enum Status { ISSUED, REVOKED }

    /** Verification answer for the public page (CC-083). */
    public enum Validity { VALID, EXPIRED, REVOKED }

    /**
     * An issued certificate: a unique number, a random verification code (printed in the QR code with the number, so
     * numbers cannot be guessed on the public page), the rendered bilingual document, and its status.
     */
    public record Issued(String number, String verificationCode, String templateCode, int templateVersion,
                         String holderNameNl, String holderNameEn, String subjectType, String subjectId,
                         Map<String, String> data, String reference, LocalDate issuedOn, LocalDate validUntil,
                         Status status, Instant revokedAt, String revocationReason, UUID documentId, Actor issuedBy,
                         Instant issuedAt) {
        public Issued {
            data = data == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(data));
        }

        public Validity validity(LocalDate today) {
            if (status == Status.REVOKED) {
                return Validity.REVOKED;
            }
            return validUntil != null && today.isAfter(validUntil) ? Validity.EXPIRED : Validity.VALID;
        }

        public Issued revoke(String reason, Instant now) {
            if (status == Status.REVOKED) {
                throw DomainException.conflict("CERTIFICATE_REVOKED", "the certificate is already revoked",
                        List.of("CC-083"));
            }
            return new Issued(number, verificationCode, templateCode, templateVersion, holderNameNl, holderNameEn,
                    subjectType, subjectId, data, reference, issuedOn, validUntil, Status.REVOKED, now, reason,
                    documentId, issuedBy, issuedAt);
        }

        /** The holder's name for the public page: initials only (SEC-014). */
        public String maskedHolder() {
            String name = holderNameEn == null || holderNameEn.isBlank() ? holderNameNl : holderNameEn;
            StringBuilder out = new StringBuilder();
            for (String part : name.strip().split("\\s+")) {
                out.append(part.charAt(0)).append(". ");
            }
            return out.toString().strip();
        }
    }

    static void text(String field, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw DomainException.validation("FIELD_INVALID", field + " is missing or too long", List.of("CC-082"),
                    field);
        }
    }
}

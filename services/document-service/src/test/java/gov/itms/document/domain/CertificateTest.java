package gov.itms.document.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-082")
public class CertificateTest {

    static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    static final Actor ISSUER = new Actor("u-issuer", "issuer");

    public static Certificate.Template template(Integer validityDays) {
        return new Certificate.Template("NO_OBJECTION", 1, "شهادة عدم ممانعة", "No-objection certificate",
                "نشهد أن {{holderNameNl}}", "We certify that {{holderNameEn}} owns {{plate}}", validityDays, ISSUER,
                NOW);
    }

    static Certificate.Issued issued(LocalDate validUntil) {
        return new Certificate.Issued("CRT-2026-00000001-7", "ABCDEFGH23", "NO_OBJECTION", 1, "محمد أحمد",
                "Mohammed Ahmed Ali", "VEHICLE", "VIN-1", Map.of("plate", "A 1"), "REF-1", TODAY, validUntil,
                Certificate.Status.ISSUED, null, null, UUID.randomUUID(), ISSUER, NOW);
    }

    @Test
    @DisplayName("CC-082: placeholders are filled; a missing value is refused")
    void fill() {
        Certificate.Template t = template(30);
        assertThat(t.fill(t.bodyEn(), Map.of("holderNameEn", "A. Person", "plate", "B 22")))
                .isEqualTo("We certify that A. Person owns B 22");
        assertThatThrownBy(() -> t.fill(t.bodyEn(), Map.of("holderNameEn", "A. Person")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("CERTIFICATE_DATA_MISSING");
        assertThatThrownBy(() -> template(0)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("VALIDITY_INVALID");
        assertThatThrownBy(() -> template(3651)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("VALIDITY_INVALID");
        assertThat(template(null).validityDays()).isNull();
    }

    @Test
    @Tag("CC-083")
    @DisplayName("CC-083: valid until the last day, expired after it, revoked once and for good")
    void validity() {
        Certificate.Issued c = issued(TODAY.plusDays(29));
        assertThat(c.validity(TODAY.plusDays(29))).isEqualTo(Certificate.Validity.VALID);
        assertThat(c.validity(TODAY.plusDays(30))).isEqualTo(Certificate.Validity.EXPIRED);
        assertThat(issued(null).validity(TODAY.plusYears(50))).isEqualTo(Certificate.Validity.VALID);

        Certificate.Issued revoked = c.revoke("issued in error", NOW);
        assertThat(revoked.validity(TODAY)).isEqualTo(Certificate.Validity.REVOKED);
        assertThat(revoked.revocationReason()).isEqualTo("issued in error");
        assertThat(c.status()).as("the original value is unchanged").isEqualTo(Certificate.Status.ISSUED);
        assertThatThrownBy(() -> revoked.revoke("again", NOW)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CERTIFICATE_REVOKED");
    }

    @Test
    @Tag("SEC-014")
    @DisplayName("SEC-014: the public page shows the holder's initials only")
    void masking() {
        assertThat(issued(null).maskedHolder()).isEqualTo("M. A. A.");
        Certificate.Issued arabicOnly = new Certificate.Issued("CRT-1", "X", "NO_OBJECTION", 1, "محمد أحمد", " ",
                "VEHICLE", "VIN-1", null, null, TODAY, null, Certificate.Status.ISSUED, null, null,
                UUID.randomUUID(), ISSUER, NOW);
        assertThat(arabicOnly.maskedHolder()).isEqualTo("م. أ.");
    }
}

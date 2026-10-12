package gov.itms.document.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.document.application.Ports;
import gov.itms.document.domain.Actor;
import gov.itms.document.domain.Certificate;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sealed PDF certificates (CC-082, CC-083, Q-067; ADR-066). */
@Tag("CC-082")
class PdfCertificateRendererTest {

    private static final Certificate.Template TEMPLATE = new Certificate.Template("VL_CERT_OWNERSHIP", 1,
            "شهادة ملكية مركبة", "Vehicle ownership certificate", "تشهد الهيئة", "The Authority certifies", null,
            new Actor("u-1", "admin"), Instant.parse("2026-10-01T00:00:00Z"));
    private static final Certificate.Issued ISSUED = new Certificate.Issued("CRT-2026-00000001-7", "ABCDEFGH23",
            "VL_CERT_OWNERSHIP", 1, "محمد أحمد", "Mohammed Ahmed", "VEHICLE", "WDB00000000000001", Map.of(),
            "VC-2026-000000001", LocalDate.of(2026, 10, 12), null, Certificate.Status.ISSUED, null, null,
            UUID.randomUUID(), new Actor("u-1", "officer"), Instant.parse("2026-10-12T08:00:00Z"));

    private static Ports.CertificateRenderer.Rendered render(CertificateSeal seal) {
        return new PdfCertificateRenderer(seal).render(TEMPLATE, ISSUED,
                "تشهد الهيئة بأن المركبة WDB00000000000001 مسجلة باسم محمد أحمد (الملف 10000000017).",
                "The Authority certifies that the vehicle WDB00000000000001 is registered to Mohammed Ahmed.",
                "https://verify.itms.example/v1/certificate-verifications/CRT-2026-00000001-7?code=ABCDEFGH23");
    }

    @Test
    @Tag("CC-083")
    @DisplayName("a sealed PDF: PAdES CAdES-detached seal over ECDSA P-256/SHA-256 verifies, text and link are on it")
    void sealedPdf() throws Exception {
        Ports.CertificateRenderer.Rendered r = render(CertificateSeal.ephemeral());
        assertThat(r.mediaType()).isEqualTo("application/pdf");
        assertThat(new String(r.content(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        SealVerifier.Checked c = SealVerifier.check(r.content());
        assertThat(c.valid()).isTrue();
        assertThat(c.subFilter()).isEqualTo("ETSI.CAdES.detached");
        assertThat(c.digestOid()).as("SHA-256").isEqualTo("2.16.840.1.101.3.4.2.1");
        assertThat(c.signer()).contains("NOT VALID FOR OFFICIAL USE");
        assertThat(c.text()).contains("Vehicle ownership certificate", "Mohammed Ahmed", "CRT-2026-00000001-7",
                "certificate-verifications/CRT-2026-00000001-7?code=ABCDEFGH23");
    }

    @Test
    @DisplayName("a changed byte breaks the seal")
    void tamperDetected() throws Exception {
        byte[] pdf = render(CertificateSeal.ephemeral()).content();
        int[] range;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            range = doc.getSignatureDictionaries().getFirst().getByteRange();
        }
        pdf[range[1] / 2] ^= 0x01; // inside the signed first revision (font stream data)
        assertThat(SealVerifier.check(pdf).valid()).isFalse();
    }

    @Test
    @DisplayName("Arabic is shaped to presentation forms in visual order")
    void arabicShaping() {
        String visual = PdfCertificateRenderer.visual("محمد");
        assertThat(visual).hasSize(4);
        assertThat(visual.codePoints().allMatch(cp -> cp >= 0xFE70 && cp <= 0xFEFF)).isTrue();
        assertThat(visual.charAt(0)).as("final dal drawn first (right to left)").isEqualTo('ﺪ');
    }

    @Test
    @DisplayName("the seal key comes from a PKCS#12 store; without one it is refused unless ephemeral is allowed")
    void keySources(@TempDir Path dir) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = g.generateKeyPair();
        X500Name name = new X500Name("CN=ITMS Test Seal");
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name,
                BigInteger.ONE, Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plusSeconds(86400)),
                name, pair.getPublic()).build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("itms-seal", pair.getPrivate(), "changeit".toCharArray(), new X509Certificate[] {cert});
        Path file = dir.resolve("seal.p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            ks.store(out, "changeit".toCharArray());
        }
        CertificateSeal seal = CertificateSeal.load(new SealProperties("PDF", "PKCS12", file.toString(), "changeit",
                "itms-seal", false));
        SealVerifier.Checked c = SealVerifier.check(render(seal).content());
        assertThat(c.valid()).isTrue();
        assertThat(c.signer()).isEqualTo("CN=ITMS Test Seal");

        assertThatThrownBy(() -> CertificateSeal.load(new SealProperties("PDF", "PKCS12", null, null, "itms-seal",
                false))).isInstanceOf(IllegalStateException.class).hasMessageContaining("HSM or KMS");
        assertThatThrownBy(() -> CertificateSeal.load(new SealProperties("PDF", "PKCS12", file.toString(), "changeit",
                "other", false))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SealProperties("DOCX", "PKCS12", null, null, "a", false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package gov.itms.document.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import gov.itms.document.application.Ports;
import gov.itms.document.domain.Actor;
import gov.itms.document.domain.Certificate;
import gov.itms.document.domain.CertificateTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-082")
class HtmlCertificateRendererTest {

    private static final String URL = "https://verify.itms.example/v1/certificate-verifications/CRT-2026-1?code=AB";

    private static Certificate.Issued issued(String number) {
        return new Certificate.Issued(number, "ABCDEFGH23", "NO_OBJECTION", 1, "محمد", "Mohammed", "VEHICLE", "V-1",
                Map.of(), null, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 11, 4), Certificate.Status.ISSUED,
                null, null, UUID.randomUUID(), new Actor("u-1", "u"), Instant.parse("2026-10-06T08:00:00Z"));
    }

    @Test
    @DisplayName("CC-082: one bilingual page, right-to-left national half, number and dates on both halves")
    void bilingualPage() {
        Ports.CertificateRenderer.Rendered r = new HtmlCertificateRenderer().render(CertificateTest.template(30),
                issued("CRT-2026-00000001-7"), "نص الشهادة", "Certificate text", URL);
        String html = new String(r.content(), StandardCharsets.UTF_8);
        assertThat(r.mediaType()).isEqualTo("text/html");
        assertThat(html).contains("dir=\"rtl\"", "dir=\"ltr\"", "شهادة عدم ممانعة", "No-objection certificate",
                "نص الشهادة", "Certificate text", "2026-11-04");
        assertThat(html.split("CRT-2026-00000001-7", -1)).as("number on both halves").hasSize(3);
    }

    @Test
    @Tag("SEC-022")
    @DisplayName("SEC-022: every value is HTML-escaped, so filled-in data cannot inject markup")
    void escaping() {
        String html = new String(new HtmlCertificateRenderer().render(CertificateTest.template(null),
                issued("CRT-<b>"), "<script>alert(1)</script>", "Tom & \"Jerry\" 'x'", URL).content(),
                StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("<script>", "CRT-<b>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;", "Tom &amp; &quot;Jerry&quot; &#39;x&#39;",
                "CRT-&lt;b&gt;", "—");
        assertThat(HtmlCertificateRenderer.esc("a<b>&\"'")).isEqualTo("a&lt;b&gt;&amp;&quot;&#39;");
    }

    @Test
    @Tag("CC-083")
    @DisplayName("CC-083: the page carries an inline SVG QR code and the verification address")
    void qrCode() {
        String svg = HtmlCertificateRenderer.qrSvg(URL);
        assertThat(svg).startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\"").contains("<path d=\"M")
                .endsWith("</svg>");
        assertThat(HtmlCertificateRenderer.qrSvg(URL)).as("deterministic").isEqualTo(svg);
        assertThat(HtmlCertificateRenderer.qrSvg(URL + "X")).isNotEqualTo(svg);
        String html = new String(new HtmlCertificateRenderer().render(CertificateTest.template(30),
                issued("CRT-1"), "a", "b", URL).content(), StandardCharsets.UTF_8);
        assertThat(html).contains(svg, HtmlCertificateRenderer.esc(URL));
    }
}

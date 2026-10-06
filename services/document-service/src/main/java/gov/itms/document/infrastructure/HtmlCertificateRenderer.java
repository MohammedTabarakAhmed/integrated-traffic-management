package gov.itms.document.infrastructure;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import gov.itms.document.application.Ports;
import gov.itms.document.domain.Certificate;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Renders a certificate as one self-contained, printable bilingual HTML page (CC-082): the national-language half
 * right-to-left, the English half left-to-right, the certificate number, dates, and a QR code (inline SVG) that opens
 * the public verification page with the number and its verification code (CC-083). Every value is HTML-escaped. A PDF
 * rendition is a later step (Q-067).
 */
class HtmlCertificateRenderer implements Ports.CertificateRenderer {

    @Override
    public Rendered render(Certificate.Template t, Certificate.Issued c, String bodyNl, String bodyEn,
                           String verificationUrl) {
        String qr = qrSvg(verificationUrl);
        String validUntil = c.validUntil() == null ? "—" : c.validUntil().toString();
        String html = """
                <!doctype html>
                <html lang="ar"><head><meta charset="utf-8"><title>%s</title>
                <style>
                body{font-family:serif;margin:24mm;color:#111}
                .half{padding:8mm 0;border-bottom:1px solid #999}
                .nl{direction:rtl;text-align:right}.en{direction:ltr;text-align:left}
                h1{font-size:20pt;margin:0 0 6mm}.meta{font-size:10pt;color:#333}
                .qr{margin-top:8mm;text-align:center}.qr svg{width:35mm;height:35mm}
                pre{white-space:pre-wrap;font-family:inherit;font-size:12pt}
                </style></head><body>
                <section class="half nl" lang="ar" dir="rtl"><h1>%s</h1><pre>%s</pre>
                <p class="meta">رقم الشهادة: %s — تاريخ الإصدار: %s — صالحة حتى: %s</p></section>
                <section class="half en" lang="en" dir="ltr"><h1>%s</h1><pre>%s</pre>
                <p class="meta">Certificate number: %s — Issued: %s — Valid until: %s</p></section>
                <div class="qr">%s<p class="meta">%s</p></div>
                </body></html>
                """.formatted(esc(t.titleEn()), esc(t.titleNl()), esc(bodyNl), esc(c.number()),
                c.issuedOn(), validUntil, esc(t.titleEn()), esc(bodyEn), esc(c.number()), c.issuedOn(), validUntil,
                qr, esc(verificationUrl));
        return new Rendered(html.getBytes(StandardCharsets.UTF_8), "text/html");
    }

    static String qrSvg(String text) {
        BitMatrix m;
        try {
            m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2,
                            EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException e) {
            throw new IllegalStateException("QR code could not be made", e);
        }
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                if (m.get(x, y)) {
                    path.append('M').append(x).append(',').append(y).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + m.getWidth() + " " + m.getHeight()
                + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\""
                + path + "\" fill=\"#000\"/></svg>";
    }

    static String esc(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }
}

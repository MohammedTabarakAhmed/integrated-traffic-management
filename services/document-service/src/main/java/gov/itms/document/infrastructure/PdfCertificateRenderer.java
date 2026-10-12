package gov.itms.document.infrastructure;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;
import gov.itms.document.application.Ports;
import gov.itms.document.domain.Certificate;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;

/**
 * Renders a certificate as a sealed PDF (CC-082, CC-083, Q-067; ADR-066): the national-language half right to left
 * (Arabic shaped to presentation forms with ICU and reordered visually, so PDFBox draws it correctly), the English half
 * left to right, number, dates and the QR code of the public verification page, then an electronic seal (PAdES
 * baseline B-B, {@link CertificateSeal}). Fonts are Noto (SIL Open Font License, resources/fonts).
 */
class PdfCertificateRenderer implements Ports.CertificateRenderer {

    private static final float MARGIN = 56;
    private static final byte[] ARABIC_FONT = resource("/fonts/NotoNaskhArabic-Regular.ttf");
    private static final byte[] LATIN_FONT = resource("/fonts/NotoSans-Regular.ttf");

    private final CertificateSeal seal;

    PdfCertificateRenderer(CertificateSeal seal) {
        this.seal = seal;
    }

    @Override
    public Rendered render(Certificate.Template t, Certificate.Issued c, String bodyNl, String bodyEn,
                           String verificationUrl) {
        try {
            byte[] unsigned = layout(t, c, bodyNl, bodyEn, verificationUrl);
            return new Rendered(sealed(unsigned, c.number()), "application/pdf");
        } catch (IOException e) {
            throw new UncheckedIOException("the certificate could not be rendered", e);
        }
    }

    private byte[] layout(Certificate.Template t, Certificate.Issued c, String bodyNl, String bodyEn,
                          String verificationUrl) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            TrueTypeFont arabicTtf = new TTFParser().parse(new RandomAccessReadBuffer(ARABIC_FONT));
            TrueTypeFont latinTtf = new TTFParser().parse(new RandomAccessReadBuffer(LATIN_FONT));
            Fonts fonts = new Fonts(PDType0Font.load(doc, arabicTtf, true), arabicTtf,
                    PDType0Font.load(doc, latinTtf, true), latinTtf);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(t.titleEn() + " " + c.number());
            info.setCreator("ITMS document-service");
            String validUntil = c.validUntil() == null ? "—" : c.validUntil().toString();
            float width = page.getMediaBox().getWidth() - 2 * MARGIN;
            float right = page.getMediaBox().getWidth() - MARGIN;
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN - 10;
                y = paragraph(out, fonts, t.titleNl(), 18, width, right, y, true);
                y = paragraph(out, fonts, bodyNl, 12, width, right, y - 8, true);
                y = paragraph(out, fonts, "رقم الشهادة: " + c.number() + " — تاريخ الإصدار: " + c.issuedOn()
                        + " — صالحة حتى: " + validUntil, 9, width, right, y - 6, true);
                out.moveTo(MARGIN, y - 10);
                out.lineTo(right, y - 10);
                out.stroke();
                y = paragraph(out, fonts, t.titleEn(), 18, width, MARGIN, y - 34, false);
                y = paragraph(out, fonts, bodyEn, 12, width, MARGIN, y - 8, false);
                y = paragraph(out, fonts, "Certificate number: " + c.number() + " — Issued: " + c.issuedOn()
                        + " — Valid until: " + validUntil, 9, width, MARGIN, y - 6, false);
                float size = 110;
                qr(out, verificationUrl, (page.getMediaBox().getWidth() - size) / 2, y - 30 - size, size);
                paragraph(out, fonts, verificationUrl, 8, width, MARGIN, y - 44 - size, false);
                paragraph(out, fonts, "Electronically sealed by " + seal.signerName(), 7, width, MARGIN, MARGIN + 10,
                        false);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            doc.save(bytes);
            return bytes.toByteArray();
        }
    }

    private byte[] sealed(byte[] unsigned, String number) throws IOException {
        try (PDDocument doc = Loader.loadPDF(unsigned)) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
            signature.setName(seal.signerName());
            signature.setReason("Electronic seal of ITMS certificate " + number);
            signature.setSignDate(Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            doc.addSignature(signature, seal::sign);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.saveIncremental(out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ text

    private record Fonts(PDType0Font arabic, TrueTypeFont arabicTtf, PDType0Font latin, TrueTypeFont latinTtf) {

        /** The Arabic font for whatever it has a glyph for (Arabic, digits, punctuation), else the Latin font. */
        PDType0Font of(int codePoint) throws IOException {
            return arabicTtf.getUnicodeCmapLookup().getGlyphId(codePoint) > 0 && !(codePoint < 0x80
                    && Character.isLetter(codePoint)) ? arabic : latin;
        }
    }

    /** Wraps and draws a paragraph; returns the y below it. RTL lines are right-aligned at {@code x}. */
    private static float paragraph(PDPageContentStream out, Fonts fonts, String text, float size, float width,
                                   float x, float y, boolean rtl) throws IOException {
        float leading = size * 1.6f;
        for (String para : text.split("\n")) {
            for (String line : wrap(fonts, para, size, width, rtl)) {
                String visual = rtl ? visual(line) : line;
                float w = width(fonts, visual, size);
                draw(out, fonts, visual, size, rtl ? x - w : x, y);
                y -= leading;
            }
        }
        return y;
    }

    private static List<String> wrap(Fonts fonts, String text, float size, float width, boolean rtl)
            throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width(fonts, rtl ? visual(candidate) : candidate, size) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** Logical Arabic text to shaped presentation forms in visual (left-to-right drawing) order. */
    static String visual(String logical) {
        try {
            String shaped = new ArabicShaping(ArabicShaping.LETTERS_SHAPE | ArabicShaping.TEXT_DIRECTION_LOGICAL
                    | ArabicShaping.LENGTH_GROW_SHRINK).shape(logical);
            Bidi bidi = new Bidi(shaped, Bidi.RTL);
            return bidi.writeReordered(Bidi.DO_MIRRORING);
        } catch (ArabicShapingException e) {
            throw new IllegalArgumentException("text could not be shaped", e);
        }
    }

    private static float width(Fonts fonts, String text, float size) throws IOException {
        float w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            w += fonts.of(cp).getStringWidth(new String(Character.toChars(cp)));
            i += Character.charCount(cp);
        }
        return w / 1000 * size;
    }

    private static void draw(PDPageContentStream out, Fonts fonts, String text, float size, float x, float y)
            throws IOException {
        float cursor = x;
        int i = 0;
        while (i < text.length()) {
            PDType0Font font = fonts.of(text.codePointAt(i));
            int j = i;
            while (j < text.length() && fonts.of(text.codePointAt(j)) == font) {
                j += Character.charCount(text.codePointAt(j));
            }
            String run = text.substring(i, j);
            out.beginText();
            out.setFont(font, size);
            out.newLineAtOffset(cursor, y);
            out.showText(run);
            out.endText();
            cursor += font.getStringWidth(run) / 1000 * size;
            i = j;
        }
    }

    private static void qr(PDPageContentStream out, String text, float x, float y, float size) throws IOException {
        BitMatrix m;
        try {
            m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2,
                            EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException e) {
            throw new IllegalStateException("QR code could not be made", e);
        }
        float cell = size / m.getWidth();
        for (int row = 0; row < m.getHeight(); row++) {
            for (int col = 0; col < m.getWidth(); col++) {
                if (m.get(col, row)) {
                    out.addRect(x + col * cell, y + size - (row + 1) * cell, cell, cell);
                }
            }
        }
        out.fill();
    }

    private static byte[] resource(String name) {
        try (InputStream in = PdfCertificateRenderer.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing font " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

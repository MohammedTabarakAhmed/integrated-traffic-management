package gov.itms.document.infrastructure;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;

/** Test helper: checks a sealed certificate PDF as a relying party would (CC-082, ADR-066). */
public final class SealVerifier {

    /** What the seal and the page say. */
    public record Checked(boolean valid, String subFilter, String signer, String digestOid, String text) {
    }

    private SealVerifier() {
    }

    public static Checked check(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDSignature sig = doc.getSignatureDictionaries().getFirst();
            // /Contents is zero-padded to its reserved size; the CMS object is the first DER value in it.
            byte[] contents;
            try (org.bouncycastle.asn1.ASN1InputStream in = new org.bouncycastle.asn1.ASN1InputStream(
                    sig.getContents(pdf))) {
                contents = in.readObject().getEncoded();
            }
            byte[] signed = sig.getSignedContent(pdf);
            CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signed), contents);
            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
            @SuppressWarnings("unchecked")
            Collection<X509CertificateHolder> certs = cms.getCertificates().getMatches(signer.getSID());
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certs.iterator().next());
            boolean valid;
            try {
                valid = signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(cert));
            } catch (org.bouncycastle.cms.CMSSignerDigestMismatchException e) {
                valid = false; // the signed bytes were changed
            }
            valid = valid && signer.getSignedAttributes().get(
                    org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_signingCertificateV2) != null;
            return new Checked(valid, sig.getSubFilter(), cert.getSubjectX500Principal().getName(),
                    signer.getDigestAlgOID(), new PDFTextStripper().getText(doc));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

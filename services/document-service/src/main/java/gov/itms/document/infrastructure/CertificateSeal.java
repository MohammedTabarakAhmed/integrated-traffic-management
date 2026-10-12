package gov.itms.document.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/**
 * The electronic seal of certificates (CC-082, Q-067; ADR-066): a detached CMS signature (ETSI CAdES, so the PDF is
 * PAdES baseline B-B) with ECDSA P-256 and SHA-256 (ADR-064 decision 5), the signing-certificate-v2 attribute and the
 * certificate chain. The key never leaves its store: a PKCS#12 file for test environments, or a PKCS#11 HSM through
 * the JDK's SunPKCS11 provider in production (keystore type {@code PKCS11}, keystore = the provider configuration).
 * An ephemeral self-signed development key is used only when {@code allow-ephemeral} is set, and says so in its name.
 */
final class CertificateSeal {

    static final String ALGORITHM = "SHA256withECDSA";
    static final String EPHEMERAL_NAME = "CN=ITMS DEVELOPMENT SEAL - NOT VALID FOR OFFICIAL USE";

    private final PrivateKey key;
    private final List<X509Certificate> chain;
    private final Provider provider;

    private CertificateSeal(PrivateKey key, List<X509Certificate> chain, Provider provider) {
        this.key = key;
        this.chain = List.copyOf(chain);
        this.provider = provider;
    }

    static CertificateSeal load(SealProperties p) {
        try {
            if (p.keystore() == null || p.keystore().isBlank()) {
                if (!p.allowEphemeral()) {
                    throw new IllegalStateException("itms.documents.seal.keystore is required: certificates are "
                            + "sealed with an HSM or KMS-held key (Q-067); allow-ephemeral is for development only");
                }
                return ephemeral();
            }
            char[] password = p.password() == null ? null : p.password().toCharArray();
            KeyStore store;
            Provider provider = null;
            if ("PKCS11".equals(p.keystoreType())) {
                Provider base = Security.getProvider("SunPKCS11");
                if (base == null) {
                    throw new IllegalStateException("the SunPKCS11 provider is not available");
                }
                provider = base.configure(p.keystore());
                Security.addProvider(provider);
                store = KeyStore.getInstance("PKCS11", provider);
                store.load(null, password);
            } else {
                store = KeyStore.getInstance(p.keystoreType());
                try (InputStream in = Files.newInputStream(Path.of(p.keystore()))) {
                    store.load(in, password);
                }
            }
            if (!(store.getKey(p.alias(), password) instanceof PrivateKey privateKey)) {
                throw new IllegalStateException("no private key under alias " + p.alias());
            }
            Certificate[] certs = store.getCertificateChain(p.alias());
            if (certs == null || certs.length == 0) {
                throw new IllegalStateException("no certificate chain under alias " + p.alias());
            }
            return new CertificateSeal(privateKey, Arrays.stream(certs).map(c -> (X509Certificate) c).toList(),
                    provider);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("the certificate seal key could not be loaded", e);
        }
    }

    /** A fresh P-256 key with a one-year self-signed certificate that names itself as not valid. */
    static CertificateSeal ephemeral() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
            KeyPair pair = g.generateKeyPair();
            Instant now = Instant.now();
            X500Name name = new X500Name(EPHEMERAL_NAME);
            ContentSigner signer = new JcaContentSignerBuilder(ALGORITHM).build(pair.getPrivate());
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                    name, new BigInteger(64, new SecureRandom()), Date.from(now.minus(Duration.ofMinutes(5))),
                    Date.from(now.plus(Duration.ofDays(365))), name, pair.getPublic()).build(signer));
            return new CertificateSeal(pair.getPrivate(), List.of(cert), null);
        } catch (GeneralSecurityException | OperatorCreationException e) {
            throw new IllegalStateException("the development seal could not be created", e);
        }
    }

    String signerName() {
        return chain.getFirst().getSubjectX500Principal().getName();
    }

    X509Certificate certificate() {
        return chain.getFirst();
    }

    /** The detached CMS signature over {@code content} (the PDF's signed byte ranges). */
    byte[] sign(InputStream content) throws IOException {
        try {
            byte[] data = content.readAllBytes();
            JcaContentSignerBuilder builder = new JcaContentSignerBuilder(ALGORITHM);
            if (provider != null) {
                builder.setProvider(provider);
            }
            ContentSigner signer = builder.build(key);
            X509Certificate cert = chain.getFirst();
            byte[] certHash = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            SigningCertificateV2 signingCertificate = new SigningCertificateV2(new ESSCertIDv2(
                    new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256), certHash));
            ASN1EncodableVector attributes = new ASN1EncodableVector();
            attributes.add(new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                    new DERSet(signingCertificate)));
            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
            generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                    new JcaDigestCalculatorProviderBuilder().build())
                    .setSignedAttributeGenerator(new DefaultSignedAttributeTableGenerator(
                            new AttributeTable(attributes)))
                    .build(signer, cert));
            generator.addCertificates(new JcaCertStore(chain));
            return generator.generate(new CMSProcessableByteArray(data), false).getEncoded();
        } catch (GeneralSecurityException | OperatorCreationException | CMSException e) {
            throw new IOException("the certificate could not be sealed", e);
        }
    }
}

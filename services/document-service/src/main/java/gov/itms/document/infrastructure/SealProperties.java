package gov.itms.document.infrastructure;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How certificates are rendered and sealed (CC-082, Q-067; ADR-066).
 *
 * @param format         {@code PDF} (sealed, production) or {@code HTML} (unsealed development preview only)
 * @param keystoreType   {@code PKCS12} (a key file) or {@code PKCS11} (an HSM through SunPKCS11)
 * @param keystore       the PKCS#12 file, or the SunPKCS11 configuration file
 * @param password       keystore password or HSM PIN, from a secret file ({@code itms.documents.seal.password})
 * @param alias          the seal key's alias
 * @param allowEphemeral development only: without a keystore, seal with a fresh self-signed key marked not valid
 */
@ConfigurationProperties("itms.documents.seal")
public record SealProperties(@DefaultValue("PDF") String format, @DefaultValue("PKCS12") String keystoreType,
                             String keystore, String password, @DefaultValue("itms-seal") String alias,
                             @DefaultValue("false") boolean allowEphemeral) {

    public SealProperties {
        if (!Set.of("PDF", "HTML").contains(format)) {
            throw new IllegalArgumentException("itms.documents.seal.format is PDF or HTML");
        }
        if (!Set.of("PKCS12", "PKCS11").contains(keystoreType)) {
            throw new IllegalArgumentException("itms.documents.seal.keystore-type is PKCS12 or PKCS11");
        }
        if (alias == null || !alias.matches("^[A-Za-z0-9._-]{1,64}$")) {
            throw new IllegalArgumentException("itms.documents.seal.alias is 1-64 letters, digits, . _ -");
        }
    }
}

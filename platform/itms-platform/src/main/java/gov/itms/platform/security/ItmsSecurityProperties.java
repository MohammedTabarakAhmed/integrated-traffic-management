package gov.itms.platform.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OIDC resource-server and authorisation settings (ADR-004, ADR-022). Issuer and audience are always validated; the
 * JWK set may be fetched from an internal address while the issuer stays the external one.
 *
 * @param issuer              expected {@code iss} claim
 * @param jwkSetUri           JWK set endpoint of the identity provider
 * @param audience            required value in {@code aud}
 * @param authorisationSource where grants come from: {@code iam} (iam-service, the default) or
 *                            {@code token-claims} (interim ADR-018 mode; flat permission claim, national scope)
 * @param permissionsClaim    flat claim holding ITMS permission strings ({@code token-claims} mode only)
 * @param rolesClaim          flat claim holding role names ({@code token-claims} mode only)
 * @param iam                 iam-service client settings ({@code iam} mode)
 */
@ConfigurationProperties("itms.security")
public record ItmsSecurityProperties(
        String issuer,
        String jwkSetUri,
        @DefaultValue("itms-api") String audience,
        @DefaultValue("iam") AuthorisationSource authorisationSource,
        @DefaultValue("permissions") String permissionsClaim,
        @DefaultValue("roles") String rolesClaim,
        @DefaultValue Iam iam) {

    public enum AuthorisationSource { IAM, TOKEN_CLAIMS }

    /**
     * @param baseUrl         iam-service base URL, e.g. {@code http://iam-service:8080}
     * @param timeout         connect and read timeout per call
     * @param cacheTtl        maximum time a resolved authorisation is reused; bounds how long a revocation takes
     *                        to reach this service
     * @param cacheMaxEntries cache bound (one entry per distinct token)
     */
    public record Iam(
            String baseUrl,
            @DefaultValue("2s") Duration timeout,
            @DefaultValue("60s") Duration cacheTtl,
            @DefaultValue("10000") int cacheMaxEntries) {
    }
}

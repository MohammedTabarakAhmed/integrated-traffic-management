package gov.itms.platform.security;

import java.time.Duration;
import java.util.List;
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
 * @param publicPaths         GET paths (Ant patterns) open without a token, for deliberately public pages such as
 *                            certificate verification (CC-083); empty by default, every other request needs a token
 */
@ConfigurationProperties("itms.security")
public record ItmsSecurityProperties(
        String issuer,
        String jwkSetUri,
        @DefaultValue("itms-api") String audience,
        @DefaultValue("iam") AuthorisationSource authorisationSource,
        @DefaultValue("permissions") String permissionsClaim,
        @DefaultValue("roles") String rolesClaim,
        @DefaultValue Iam iam,
        List<String> publicPaths) {

    public enum AuthorisationSource { IAM, TOKEN_CLAIMS }

    public ItmsSecurityProperties {
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
        for (String p : publicPaths) {
            if (p == null || !p.startsWith("/v1/") || p.contains("..")) {
                throw new IllegalArgumentException("itms.security.public-paths must be /v1/ paths (SEC-001)");
            }
        }
    }

    /**
     * @param baseUrl         iam-service base URL, e.g. {@code http://iam-service:8080}
     * @param timeout         connect and read timeout per call
     * @param cacheTtl        maximum time a resolved authorisation is reused; bounds how long a revocation takes
     *                        to reach this service
     * @param cacheMaxEntries cache bound (one entry per distinct token)
     */
    /**
     * The iam grant lookup (ADR-022). Approved ranges (NFR-042): timeout above 0 and at most 30 s; cache time 0 to
     * 5 min (a leaver's grants are dropped within it, SEC-007); 1 to 1,000,000 cached tokens.
     */
    public record Iam(
            String baseUrl,
            @DefaultValue("2s") Duration timeout,
            @DefaultValue("60s") Duration cacheTtl,
            @DefaultValue("10000") int cacheMaxEntries) {

        public Iam {
            if (timeout == null || timeout.isNegative() || timeout.isZero()
                    || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("itms.security.iam.timeout must be more than 0 and at most 30s");
            }
            if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("itms.security.iam.cache-ttl must be 0 to 5m (SEC-007, ADR-022)");
            }
            if (cacheMaxEntries < 1 || cacheMaxEntries > 1_000_000) {
                throw new IllegalArgumentException("itms.security.iam.cache-max-entries must be 1-1000000");
            }
        }
    }
}

package gov.itms.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OIDC resource-server settings (ADR-004). Issuer and audience are always validated; the JWK set may be fetched from
 * an internal address while the issuer stays the external one.
 *
 * @param issuer           expected {@code iss} claim
 * @param jwkSetUri        JWK set endpoint of the identity provider
 * @param audience         required value in {@code aud}
 * @param permissionsClaim flat claim holding ITMS permission strings (CC-001)
 * @param rolesClaim       flat claim holding role names (recorded in audit, CC-120)
 */
@ConfigurationProperties("itms.security")
public record ItmsSecurityProperties(
        String issuer,
        String jwkSetUri,
        @DefaultValue("itms-api") String audience,
        @DefaultValue("permissions") String permissionsClaim,
        @DefaultValue("roles") String rolesClaim) {
}

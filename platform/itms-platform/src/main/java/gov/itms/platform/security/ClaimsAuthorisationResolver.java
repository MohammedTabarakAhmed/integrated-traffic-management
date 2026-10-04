package gov.itms.platform.security;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Interim source (ADR-018): permissions from a flat token claim, national scope, no expiry beyond the token. Only
 * used when {@code itms.security.authorisation-source=token-claims}; production uses iam-service (ADR-022).
 * Values that are not well-formed permission strings are ignored.
 */
public class ClaimsAuthorisationResolver implements AuthorisationResolver {

    static final String CLAIMS_ROLE = "TOKEN_CLAIMS";
    private static final Pattern PERMISSION = Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*){1,4}$");

    private final String permissionsClaim;
    private final String rolesClaim;

    public ClaimsAuthorisationResolver(String permissionsClaim, String rolesClaim) {
        this.permissionsClaim = permissionsClaim;
        this.rolesClaim = rolesClaim;
    }

    @Override
    public Authorisation resolve(Jwt jwt) {
        Set<String> permissions = Objects.requireNonNullElse(jwt.getClaimAsStringList(permissionsClaim), List.<String>of())
                .stream().filter(p -> PERMISSION.matcher(p).matches()).collect(Collectors.toSet());
        List<String> roles = Objects.requireNonNullElse(jwt.getClaimAsStringList(rolesClaim), List.of());
        if (permissions.isEmpty()) {
            return new Authorisation(jwt.getSubject(), List.of(), jwt.getIssuedAt());
        }
        // The token does not say which role carries which permission, so every role carries the full set.
        List<Authorisation.Grant> grants = (roles.isEmpty() ? List.of(CLAIMS_ROLE) : roles).stream()
                .map(r -> new Authorisation.Grant(r, permissions, Scopes.NATIONAL, jwt.getExpiresAt()))
                .toList();
        return new Authorisation(jwt.getSubject(), grants, jwt.getIssuedAt());
    }
}

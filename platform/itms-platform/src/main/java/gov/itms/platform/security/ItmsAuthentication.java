package gov.itms.platform.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * A validated token plus the subject's resolved {@link Authorisation}. Authorities are the permissions held in at
 * least one scope, so {@code @PreAuthorize("hasAuthority(...)")} keeps working; data scopes are checked with
 * {@link Authorisation#allows} / {@link Authorisation#covers}.
 */
public class ItmsAuthentication extends JwtAuthenticationToken {

    private final transient Authorisation authorisation;

    public ItmsAuthentication(Jwt jwt, String name, Authorisation authorisation) {
        super(jwt, authorisation.permissions().stream().map(SimpleGrantedAuthority::new).toList(), name);
        this.authorisation = authorisation;
    }

    public Authorisation authorisation() {
        return authorisation;
    }
}

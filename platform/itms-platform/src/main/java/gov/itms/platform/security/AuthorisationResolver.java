package gov.itms.platform.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves what an authenticated subject may do (CC-001, ADR-022). The identity provider only authenticates; the
 * grants come from iam-service. iam-service itself supplies a local implementation that reads its own store.
 */
public interface AuthorisationResolver {

    /**
     * @throws AuthorisationUnavailableException if the source of grants cannot be reached; callers fail closed
     */
    Authorisation resolve(Jwt jwt);
}

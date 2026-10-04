package gov.itms.iam.infrastructure;

import gov.itms.iam.application.IamService;
import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.AuthorisationResolver;
import gov.itms.platform.security.AuthorisationUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * iam-service resolves its own callers' grants from its own store (no HTTP call to itself), with the same rules every
 * other service gets through {@code GET /v1/me/authorisation} (ADR-022).
 */
class LocalAuthorisationResolver implements AuthorisationResolver {

    private final IamService service;

    LocalAuthorisationResolver(IamService service) {
        this.service = service;
    }

    @Override
    public Authorisation resolve(Jwt jwt) {
        try {
            return service.effectiveAuthorisation(jwt.getSubject());
        } catch (DataAccessException e) {
            throw new AuthorisationUnavailableException("authorisation store unavailable", e);
        }
    }
}

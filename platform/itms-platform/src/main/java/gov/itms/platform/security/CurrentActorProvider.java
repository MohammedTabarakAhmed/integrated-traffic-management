package gov.itms.platform.security;

import gov.itms.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the {@link CurrentActor} and the {@link Authorisation} of the current request from the validated token,
 * the resolved grants and request metadata.
 */
public class CurrentActorProvider {

    public static final String CHANNEL_HEADER = "X-Channel";

    public CurrentActor current() {
        JwtAuthenticationToken token = token();
        var jwt = token.getToken();
        String username = Objects.requireNonNullElse(jwt.getClaimAsString("preferred_username"), jwt.getSubject());
        List<String> roles = token instanceof ItmsAuthentication itms ? itms.authorisation().roles() : List.of();

        HttpServletRequest request = currentRequest();
        String channel = "UNKNOWN";
        String ip = null;
        String correlationId = null;
        if (request != null) {
            // In production the gateway/BFF overwrites X-Channel; only known values are accepted.
            String header = request.getHeader(CHANNEL_HEADER);
            if (header != null && CurrentActor.CHANNELS.contains(header.toUpperCase(Locale.ROOT))) {
                channel = header.toUpperCase(Locale.ROOT);
            }
            ip = request.getRemoteAddr();
            Object cid = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
            correlationId = cid == null ? null : cid.toString();
        }
        return new CurrentActor(jwt.getSubject(), username, roles, channel, ip, correlationId);
    }

    /**
     * The signed-in customer (ADR-067 d): {@code subject_type=CUSTOMER} and a {@code national_id} in the token. Any
     * other caller, staff included, is refused with 403, so a {@code /v1/me/...} endpoint never serves a staff token
     * or a customer token without a national id.
     */
    public CurrentCustomer customer() {
        var jwt = token().getToken();
        String nationalId = jwt.getClaimAsString(SubjectType.NATIONAL_ID_CLAIM);
        if (!SubjectType.isCustomer(jwt) || nationalId == null || nationalId.isBlank()) {
            throw new AccessDeniedException("Only a signed-in customer with a national id may use this endpoint");
        }
        return new CurrentCustomer(jwt.getSubject(), nationalId.strip());
    }

    /** Grants of the current subject (CC-001). Empty if the request was not authorised through ITMS. */
    public Authorisation authorisation() {
        JwtAuthenticationToken token = token();
        return token instanceof ItmsAuthentication itms ? itms.authorisation()
                : new Authorisation(token.getToken().getSubject(), List.of(), null);
    }

    private static JwtAuthenticationToken token() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("No authenticated user in context");
        }
        return token;
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }
}

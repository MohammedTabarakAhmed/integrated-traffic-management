package gov.itms.platform.security;

import gov.itms.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Resolves the {@link CurrentActor} of the current request from the validated token and request metadata. */
public class CurrentActorProvider {

    public static final String CHANNEL_HEADER = "X-Channel";

    private final ItmsSecurityProperties properties;

    public CurrentActorProvider(ItmsSecurityProperties properties) {
        this.properties = properties;
    }

    public CurrentActor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("No authenticated user in context");
        }
        var jwt = token.getToken();
        String username = Objects.requireNonNullElse(jwt.getClaimAsString("preferred_username"), jwt.getSubject());
        List<String> roles = Objects.requireNonNullElse(jwt.getClaimAsStringList(properties.rolesClaim()), List.of());

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

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }
}

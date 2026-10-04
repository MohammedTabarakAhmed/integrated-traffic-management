package gov.itms.platform.security;

import gov.itms.platform.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.support.RequestContextUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs after bearer-token authentication and replaces the token's authentication with an {@link ItmsAuthentication}
 * carrying the subject's grants (CC-001, ADR-022). Token claims never grant permissions in {@code iam} mode. If the
 * grants cannot be resolved the request ends with 503 and a problem response: deny, never allow.
 */
public class AuthorisationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthorisationFilter.class);

    private final AuthorisationResolver resolver;
    private final MessageSource messages;
    private final ObjectMapper json;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    public AuthorisationFilter(AuthorisationResolver resolver, MessageSource messages, ObjectMapper json) {
        this.resolver = resolver;
        this.messages = messages;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication current = contexts.getContext().getAuthentication();
        if (current instanceof JwtAuthenticationToken token && !(current instanceof ItmsAuthentication)) {
            Authorisation authorisation;
            try {
                authorisation = resolver.resolve(token.getToken());
            } catch (AuthorisationUnavailableException e) {
                log.warn("Authorisation unavailable; request refused: {}", e.getMessage());
                writeUnavailable(request, response);
                return;
            }
            SecurityContext context = contexts.createEmptyContext();
            context.setAuthentication(new ItmsAuthentication(token.getToken(), token.getName(), authorisation));
            contexts.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private void writeUnavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String code = "AUTHORISATION_UNAVAILABLE";
        String detail = messages.getMessage("problem." + code, null,
                "Access rights cannot be checked right now, so the request was not processed. Try again shortly.",
                RequestContextUtils.getLocale(request));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:itms:problem:authorisation-unavailable");
        body.put("title", HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("detail", detail);
        body.put("code", code);
        body.put("requirementRefs", List.of("CC-001"));
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader("Retry-After", "5");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(body));
    }
}

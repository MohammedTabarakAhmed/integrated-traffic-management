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
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Customer step-up on sensitive services (SEC-002, Q-007, ADR-067 c). Runs after the {@link AuthorisationFilter}: a
 * customer token on a configured path without an accepted {@code acr} ends with 401 and
 * {@code WWW-Authenticate: Bearer error="insufficient_user_authentication"} naming the accepted values (RFC 9470), so
 * the portal can ask the identity provider for a stronger sign-in and retry. Staff tokens pass: their MFA is
 * enforced at sign-in.
 */
public class StepUpFilter extends OncePerRequestFilter {

    static final String CODE = "STEP_UP_REQUIRED";

    private record Rule(String method, PathPattern pattern) {
    }

    private final List<Rule> rules;
    private final List<String> acrValues;
    private final MessageSource messages;
    private final ObjectMapper json;

    public StepUpFilter(ItmsSecurityProperties.StepUp settings, MessageSource messages, ObjectMapper json) {
        PathPatternParser parser = new PathPatternParser();
        this.rules = settings.paths().stream().map(p -> p.split(" ", 2))
                .map(p -> new Rule(p[0], parser.parse(p[1]))).toList();
        this.acrValues = settings.acrValues();
        this.messages = messages;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return rules.isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token && SubjectType.isCustomer(token.getToken())
                && sensitive(request) && !accepted(token.getToken().getClaimAsString("acr"))) {
            writeStepUp(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean accepted(String acr) {
        return acr != null && acrValues.contains(acr);
    }

    private boolean sensitive(HttpServletRequest request) {
        PathContainer path = PathContainer.parsePath(request.getRequestURI().substring(
                request.getContextPath().length()));
        return rules.stream().anyMatch(r -> r.method().equals(request.getMethod()) && r.pattern().matches(path));
    }

    private void writeStepUp(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String detail = messages.getMessage("problem." + CODE, null,
                "This service needs a stronger sign-in. Sign in again with your one-time password and retry.",
                RequestContextUtils.getLocale(request));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:itms:problem:step-up-required");
        body.put("title", HttpStatus.UNAUTHORIZED.getReasonPhrase());
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("detail", detail);
        body.put("code", CODE);
        body.put("requirementRefs", List.of("SEC-002"));
        body.put("acrValues", acrValues);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_user_authentication\", "
                + "error_description=\"A stronger sign-in is required\", acr_values=\""
                + String.join(" ", acrValues) + "\"");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(body));
    }
}

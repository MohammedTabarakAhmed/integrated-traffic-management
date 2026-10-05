package gov.itms.platform.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Matches the probe and scrape requests that need no token (NFR-040, ADR-037): {@code /actuator/health/**} and
 * {@code /actuator/prometheus}, and only when they arrive on the separate management port
 * ({@code management.server.port}). The management port is reachable only from the operations network (HLD §9);
 * the same paths on the API port stay behind authentication, and no other actuator endpoint is opened.
 */
public final class ManagementRequests implements RequestMatcher {

    /** The web server namespace Spring Boot gives the separate management server. */
    static final String MANAGEMENT_NAMESPACE = "management";

    private static final Set<String> OPEN_PATHS = Set.of("/actuator/prometheus", "/actuator/health");

    @Override
    public boolean matches(HttpServletRequest request) {
        return isOpenPath(path(request)) && onManagementServer(request);
    }

    static boolean isOpenPath(String path) {
        return OPEN_PATHS.contains(path) || path.startsWith("/actuator/health/") && !path.contains("..");
    }

    private static String path(HttpServletRequest request) {
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return path.isEmpty() ? request.getRequestURI() : path;
    }

    private static boolean onManagementServer(HttpServletRequest request) {
        WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(request.getServletContext());
        return WebServerApplicationContext.hasServerNamespace(context, MANAGEMENT_NAMESPACE);
    }
}

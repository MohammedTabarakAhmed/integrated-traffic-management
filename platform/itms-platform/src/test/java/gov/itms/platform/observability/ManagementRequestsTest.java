package gov.itms.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.StaticWebApplicationContext;

class ManagementRequestsTest {

    private final ManagementRequests matcher = new ManagementRequests();

    /** A web context with a server namespace, as Spring Boot creates for the separate management server. */
    static final class NamespacedContext extends StaticWebApplicationContext implements WebServerApplicationContext {

        private final String namespace;

        NamespacedContext(String namespace) {
            this.namespace = namespace;
        }

        @Override
        public WebServer getWebServer() {
            return null;
        }

        @Override
        public String getServerNamespace() {
            return namespace;
        }
    }

    private static MockHttpServletRequest request(String namespace, String path) {
        MockServletContext servletContext = new MockServletContext();
        if (namespace != null) {
            servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE,
                    new NamespacedContext(namespace));
        }
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "GET", path);
        request.setServletPath(path);
        return request;
    }

    @Test
    @Tag("NFR-040")
    @DisplayName("NFR-040: probes and the Prometheus scrape are open on the management server only")
    void opensProbesAndScrapeOnTheManagementServer() {
        assertThat(matcher.matches(request("management", "/actuator/prometheus"))).isTrue();
        assertThat(matcher.matches(request("management", "/actuator/health"))).isTrue();
        assertThat(matcher.matches(request("management", "/actuator/health/readiness"))).isTrue();

        // The API server (no namespace, or a plain web context) never matches.
        assertThat(matcher.matches(request(null, "/actuator/prometheus"))).isFalse();
        assertThat(matcher.matches(request("", "/actuator/prometheus"))).isFalse();
        assertThat(matcher.matches(request("server", "/actuator/prometheus"))).isFalse();
    }

    @Test
    @Tag("NFR-040")
    @Tag("SEC-022")
    @DisplayName("NFR-040: no other actuator endpoint is opened, even on the management server")
    void opensNothingElse() {
        for (String path : new String[] {"/actuator", "/actuator/loggers", "/actuator/env", "/actuator/info",
                "/actuator/prometheus/x", "/actuator/healthx", "/actuator/health/../env", "/v1/roles"}) {
            assertThat(matcher.matches(request("management", path))).as(path).isFalse();
        }
    }
}

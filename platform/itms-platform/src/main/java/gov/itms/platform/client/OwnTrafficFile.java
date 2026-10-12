package gov.itms.platform.client;

import gov.itms.platform.web.CorrelationIdFilter;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The traffic file number of the signed-in customer (ADR-067 d), for services that keep records by file number but
 * not the national id. It relays the customer's own token to traffic-file-service {@code GET /v1/me/traffic-file},
 * which finds the file by the token's {@code national_id}: the calling service needs no permission to read traffic
 * files, and the answer can only ever be the caller's own file.
 */
public class OwnTrafficFile {

    static final String PATH = "/v1/me/traffic-file";

    private final RestClient client;

    public OwnTrafficFile(RestClient client) {
        this.client = client;
    }

    /** A client of traffic-file-service at {@code baseUrl}; calls time out after {@code timeout}. */
    public static OwnTrafficFile create(String baseUrl, Duration timeout, ObservationRegistry observations) {
        URI uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalStateException("the traffic-file base URL must be an http or https URL");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalStateException("the traffic-file timeout must be more than 0 and at most 1 minute");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return new OwnTrafficFile(RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .observationRegistry(observations).build());
    }

    private record FileBody(String fileNumber) {
    }

    /**
     * The customer's file number; empty when they have no traffic file yet.
     *
     * @throws ServiceCallException when traffic-file-service cannot answer
     */
    public Optional<String> fileNumber() {
        String token = currentToken();
        try {
            FileBody body = client.get().uri(PATH).headers(h -> headers(h, token)).retrieve().body(FileBody.class);
            return Optional.ofNullable(body).map(FileBody::fileNumber);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw new ServiceCallException("traffic-file-service answered " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new ServiceCallException("traffic-file-service is unavailable", e);
        }
    }

    private static void headers(HttpHeaders headers, String token) {
        headers.setBearerAuth(token);
        headers.set("X-Channel", "PORTAL");
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            headers.set(CorrelationIdFilter.HEADER, correlationId);
        }
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, LocaleContextHolder.getLocale().toLanguageTag());
    }

    private static String currentToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("No authenticated user in context");
        }
        return token.getToken().getTokenValue();
    }
}

package gov.itms.platform.client;

import gov.itms.platform.web.CorrelationIdFilter;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds clients for calls between ITMS services through their APIs (the project guide §2: no access to another service's
 * database; INT-004: OAuth2 client credentials). Each call carries:
 * <ul>
 *   <li>the calling service's own bearer token ({@link ServiceTokenProvider}); the target service resolves that
 *       account's grants from iam-service like any other caller's, so a service account has only the roles granted
 *       to it;</li>
 *   <li>the current correlation id ({@code X-Correlation-Id}) and the W3C trace context ({@code traceparent}, from
 *       the observation registry), so the call can be traced end to end (NFR-040, ADR-037); each call is also
 *       measured as {@code http.client.requests};</li>
 *   <li>the current request's language ({@code Accept-Language}), unless the call sets one, so customer-facing
 *       messages come back in the customer's language (CC-010).</li>
 * </ul>
 * mTLS between services is added at the platform level when the service mesh is in place (ADR-034).
 */
public final class ServiceClients {

    private ServiceClients() {
    }

    /**
     * A client of {@code properties.baseUrl()}, with timeouts on every call and token; calls (token requests
     * included) are observed through {@code observations}.
     */
    public static RestClient create(ServiceClientProperties properties, Clock clock,
                                    ObservationRegistry observations) {
        JdkClientHttpRequestFactory factory = requestFactory(properties.timeout());
        ServiceTokenProvider tokens = new ServiceTokenProvider(
                RestClient.builder().baseUrl(properties.tokenUri()).requestFactory(factory)
                        .observationRegistry(observations).build(),
                properties.clientId(), properties.clientSecret(), clock);
        return create(RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory)
                .observationRegistry(observations), tokens);
    }

    /** A client built on {@code target} (for example bound to a mock server in tests). */
    public static RestClient create(RestClient.Builder target, ServiceTokenProvider tokens) {
        return target.requestInterceptor((request, body, execution) -> {
            String token = tokens.token();
            HttpHeaders headers = request.getHeaders();
            headers.setBearerAuth(token);
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                headers.set(CorrelationIdFilter.HEADER, correlationId);
            }
            if (!headers.containsHeader(HttpHeaders.ACCEPT_LANGUAGE)) {
                headers.set(HttpHeaders.ACCEPT_LANGUAGE, LocaleContextHolder.getLocale().toLanguageTag());
            }
            ClientHttpResponse response = execution.execute(request, body);
            if (response.getStatusCode().value() == 401) {
                tokens.invalidate(token);
            }
            return response;
        }).build();
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return factory;
    }
}

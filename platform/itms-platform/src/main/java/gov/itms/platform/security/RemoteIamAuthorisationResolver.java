package gov.itms.platform.security;

import gov.itms.platform.web.CorrelationIdFilter;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fetches the caller's grants from iam-service ({@code GET /v1/me/authorisation}) by forwarding the caller's own
 * bearer token, so no extra service credential is needed and iam-service re-validates the token (ADR-022).
 * <ul>
 *   <li>Results are cached per token for at most {@code cacheTtl}, never beyond the token's expiry or the earliest
 *       grant expiry, so revocations take effect within {@code cacheTtl}.</li>
 *   <li>Any failure throws {@link AuthorisationUnavailableException}: the request is refused, never allowed.</li>
 * </ul>
 */
public class RemoteIamAuthorisationResolver implements AuthorisationResolver {

    private final RestClient client;
    private final Duration cacheTtl;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Authorisation authorisation, Instant expiresAt) {
    }

    /** Calls are observed through {@code observations}, which also sends the trace context (NFR-040). */
    public RemoteIamAuthorisationResolver(String baseUrl, Duration timeout, Duration cacheTtl, int maxEntries,
                                          Clock clock, ObservationRegistry observations) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(Objects.requireNonNull(baseUrl)).requestFactory(factory)
                .observationRegistry(observations).build();
        this.cacheTtl = cacheTtl;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /** For tests: a client built elsewhere (for example bound to a mock server). */
    RemoteIamAuthorisationResolver(RestClient client, Duration cacheTtl, int maxEntries, Clock clock) {
        this.client = client;
        this.cacheTtl = cacheTtl;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    @Override
    public Authorisation resolve(Jwt jwt) {
        Instant now = clock.instant();
        String key = sha256(jwt.getTokenValue());
        Cached hit = cache.get(key);
        if (hit != null && now.isBefore(hit.expiresAt())) {
            return hit.authorisation();
        }
        Authorisation fetched = fetch(jwt);
        if (!jwt.getSubject().equals(fetched.subjectId())) {
            throw new AuthorisationUnavailableException("iam-service answered for a different subject", null);
        }
        store(key, fetched, expiry(now, jwt, fetched));
        return fetched;
    }

    private Authorisation fetch(Jwt jwt) {
        try {
            Authorisation result = client.get().uri("/v1/me/authorisation")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue())
                    .headers(h -> {
                        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
                        if (correlationId != null) {
                            h.set(CorrelationIdFilter.HEADER, correlationId);
                        }
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Authorisation.class);
            if (result == null || result.subjectId() == null) {
                throw new AuthorisationUnavailableException("iam-service returned no authorisation", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new AuthorisationUnavailableException("iam-service could not be consulted", e);
        }
    }

    private Instant expiry(Instant now, Jwt jwt, Authorisation authorisation) {
        Instant expiry = now.plus(cacheTtl);
        if (jwt.getExpiresAt() != null && jwt.getExpiresAt().isBefore(expiry)) {
            expiry = jwt.getExpiresAt();
        }
        for (Authorisation.Grant g : authorisation.grants()) {
            if (g.validTo() != null && g.validTo().isBefore(expiry)) {
                expiry = g.validTo();
            }
        }
        return expiry;
    }

    private void store(String key, Authorisation authorisation, Instant expiresAt) {
        if (cache.size() >= maxEntries) {
            Instant now = clock.instant();
            cache.values().removeIf(c -> !now.isBefore(c.expiresAt()));
            if (cache.size() >= maxEntries) {
                cache.clear();
            }
        }
        cache.put(key, new Cached(authorisation, expiresAt));
    }

    int cacheSize() {
        return cache.size();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

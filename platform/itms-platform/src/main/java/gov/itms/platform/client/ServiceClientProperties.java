package gov.itms.platform.client;

import java.net.URI;
import java.time.Duration;

/**
 * Settings of one outgoing service-to-service client (INT-004, ADR-034): the target service and the calling
 * service's own account at the identity provider (OAuth2 client credentials). The secret comes from the environment
 * (SEC-004) and is never printed: {@link #toString()} masks it.
 *
 * @param baseUrl      base URL of the target service, e.g. {@code http://hold-eligibility-service:8080}
 * @param tokenUri     token endpoint of the identity provider
 * @param clientId     this service's client id
 * @param clientSecret this service's client secret
 * @param timeout      connect and read timeout per call (token and target)
 */
public record ServiceClientProperties(String baseUrl, String tokenUri, String clientId, String clientSecret,
                                      Duration timeout) {

    /** Fails start-up when a setting is missing or malformed; {@code name} is the property prefix, for the message. */
    public ServiceClientProperties validate(String name) {
        url(name + ".base-url", baseUrl);
        url(name + ".token-uri", tokenUri);
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException(name + ".client-id is required");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(name + ".client-secret is required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalStateException(name + ".timeout must be more than 0 and at most 1 minute");
        }
        return this;
    }

    private static void url(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(property + " is not a valid URL");
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalStateException(property + " must be an http or https URL");
        }
    }

    @Override
    public String toString() {
        return "ServiceClientProperties[baseUrl=" + baseUrl + ", tokenUri=" + tokenUri + ", clientId=" + clientId
                + ", clientSecret=******, timeout=" + timeout + "]";
    }
}

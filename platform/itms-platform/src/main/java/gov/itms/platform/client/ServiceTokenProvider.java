package gov.itms.platform.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The calling service's own access token, from the identity provider by OAuth2 client credentials (RFC 6749 §4.4,
 * client authentication by HTTP Basic, §2.3.1). The token is reused until shortly before it expires and dropped when
 * the target service rejects it. Neither the secret nor the token is ever logged or put in an exception message.
 */
public class ServiceTokenProvider {

    /** Renew this long before expiry (or at half the lifetime, if that is sooner), so a token never expires in use. */
    static final Duration RENEW_BEFORE = Duration.ofSeconds(30);

    private final RestClient tokenClient;
    private final String basicCredentials;
    private final Clock clock;
    private String token;
    private Instant renewAt = Instant.MIN;

    /**
     * @param tokenClient a client whose base URL is the token endpoint (built by {@link ServiceClients})
     */
    public ServiceTokenProvider(RestClient tokenClient, String clientId, String clientSecret, Clock clock) {
        this.tokenClient = tokenClient;
        this.basicCredentials = Base64.getEncoder().encodeToString(
                (form(clientId) + ":" + form(clientSecret)).getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    /** A valid access token, fetched when there is none or it is about to expire. */
    public synchronized String token() {
        Instant now = clock.instant();
        if (token != null && now.isBefore(renewAt)) {
            return token;
        }
        Map<?, ?> response = fetch();
        Object accessToken = response.get("access_token");
        Object tokenType = response.get("token_type");
        Object expiresIn = response.get("expires_in");
        if (!(accessToken instanceof String value) || value.isBlank()
                || !(tokenType instanceof String type) || !"bearer".equalsIgnoreCase(type)
                || !(expiresIn instanceof Number seconds) || seconds.longValue() <= 0) {
            throw new ServiceCallException("the identity provider returned no usable bearer token", null);
        }
        Duration lifetime = Duration.ofSeconds(seconds.longValue());
        Duration early = lifetime.dividedBy(2).compareTo(RENEW_BEFORE) < 0 ? lifetime.dividedBy(2) : RENEW_BEFORE;
        token = value;
        renewAt = now.plus(lifetime).minus(early);
        return token;
    }

    /** Forgets {@code rejected} (the target answered 401), so the next call fetches a new token. */
    public synchronized void invalidate(String rejected) {
        if (rejected != null && rejected.equals(token)) {
            token = null;
            renewAt = Instant.MIN;
        }
    }

    private Map<?, ?> fetch() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        try {
            Map<?, ?> body = tokenClient.post()
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basicCredentials)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                throw new ServiceCallException("the identity provider returned an empty token response", null);
            }
            return body;
        } catch (RestClientException e) {
            // the exception text may echo the response; only its type is kept
            throw new ServiceCallException("the service token could not be obtained (" + e.getClass().getSimpleName()
                    + ")", null);
        }
    }

    private static String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

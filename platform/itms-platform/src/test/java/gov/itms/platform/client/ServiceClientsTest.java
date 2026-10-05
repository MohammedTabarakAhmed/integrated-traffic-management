package gov.itms.platform.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import gov.itms.platform.web.CorrelationIdFilter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Service-to-service calls with the caller's own client-credentials token (INT-004, ADR-034). */
class ServiceClientsTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    private static final String TOKEN_JSON = """
            {"access_token":"%s","token_type":"Bearer","expires_in":300}""";

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private MockRestServiceServer idp;
    private MockRestServiceServer target;
    private RestClient client;

    @BeforeEach
    void setUp() {
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        RestClient.Builder idpBuilder = RestClient.builder().baseUrl("http://idp/token");
        idp = MockRestServiceServer.bindTo(idpBuilder).build();
        ServiceTokenProvider tokens = new ServiceTokenProvider(idpBuilder.build(), "itms-txn", "s3cr:et", clock);
        RestClient.Builder targetBuilder = RestClient.builder().baseUrl("http://hold");
        target = MockRestServiceServer.bindTo(targetBuilder).build();
        client = ServiceClients.create(targetBuilder, tokens);
    }

    @AfterEach
    void clear() {
        MDC.clear();
        LocaleContextHolder.resetLocaleContext();
    }

    private void expectToken(String token) {
        idp.expect(requestTo("http://idp/token")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString("itms-txn:s3cr%3Aet".getBytes())))
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess(TOKEN_JSON.formatted(token), MediaType.APPLICATION_JSON));
    }

    private String call() {
        return client.get().uri("/v1/ping").retrieve().body(String.class);
    }

    @Test
    @Tag("INT-004")
    @DisplayName("INT-004: calls carry the service's own token, the correlation id and the caller's language")
    void callsCarryTokenCorrelationAndLanguage() {
        expectToken("tok-1");
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-1");
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ar"));
        target.expect(requestTo("http://hold/v1/ping")).andExpect(header("Authorization", "Bearer tok-1"))
                .andExpect(header(CorrelationIdFilter.HEADER, "corr-1"))
                .andExpect(header("Accept-Language", "ar"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        assertThat(call()).isEqualTo("ok");
        idp.verify();
        target.verify();
    }

    @Test
    @Tag("INT-004")
    @DisplayName("INT-004: the token is reused until shortly before expiry, then renewed")
    void tokenIsCachedAndRenewed() {
        expectToken("tok-1");
        target.expect(requestTo("http://hold/v1/ping")).andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess("1", MediaType.TEXT_PLAIN));
        target.expect(requestTo("http://hold/v1/ping")).andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess("2", MediaType.TEXT_PLAIN));
        call();
        now.set(NOW.plusSeconds(300).minus(ServiceTokenProvider.RENEW_BEFORE).minusSeconds(1));
        call();
        idp.verify();

        idp.reset();
        target.verify();
        target.reset();
        expectToken("tok-2");
        target.expect(requestTo("http://hold/v1/ping")).andExpect(header("Authorization", "Bearer tok-2"))
                .andRespond(withSuccess("3", MediaType.TEXT_PLAIN));
        now.set(NOW.plusSeconds(300).minus(ServiceTokenProvider.RENEW_BEFORE));
        assertThat(call()).isEqualTo("3");
        idp.verify();
        target.verify();
    }

    @Test
    @Tag("INT-004")
    @DisplayName("INT-004: a token rejected by the target (401) is dropped and the next call gets a new one")
    void rejectedTokenIsDropped() {
        expectToken("tok-1");
        target.expect(requestTo("http://hold/v1/ping")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(this::call).isInstanceOf(HttpClientErrorException.Unauthorized.class);
        idp.verify();

        idp.reset();
        target.verify();
        target.reset();
        expectToken("tok-2");
        target.expect(requestTo("http://hold/v1/ping")).andExpect(header("Authorization", "Bearer tok-2"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
        assertThat(call()).isEqualTo("ok");
        idp.verify();
    }

    @Test
    @Tag("INT-004")
    @Tag("SEC-004")
    @DisplayName("INT-004/SEC-004: an unusable token answer fails the call without echoing secrets or tokens")
    void unusableTokenAnswers() {
        idp.expect(requestTo("http://idp/token")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .body("{\"error\":\"invalid_client\",\"secret\":\"s3cr:et\"}").contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(this::call).isInstanceOf(ServiceCallException.class)
                .hasMessageNotContaining("s3cr").hasNoCause();

        for (String bad : new String[] {
                "{\"access_token\":\"t\",\"token_type\":\"mac\",\"expires_in\":300}",
                "{\"access_token\":\"\",\"token_type\":\"Bearer\",\"expires_in\":300}",
                "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":0}",
                "{\"access_token\":\"t\",\"token_type\":\"Bearer\"}"}) {
            idp.reset();
            idp.expect(requestTo("http://idp/token")).andRespond(withSuccess(bad, MediaType.APPLICATION_JSON));
            assertThatThrownBy(this::call).as(bad).isInstanceOf(ServiceCallException.class);
        }
    }

    @Test
    @Tag("SEC-004")
    @DisplayName("SEC-004: client settings are validated at start-up and never print the secret")
    void propertiesAreValidatedAndMasked() {
        ServiceClientProperties ok = new ServiceClientProperties("http://hold:8080", "https://idp/token", "itms-txn",
                "top-secret", Duration.ofSeconds(2));
        assertThat(ok.validate("itms.clients.eligibility")).isSameAs(ok);
        assertThat(ok.toString()).doesNotContain("top-secret").contains("******");

        assertThatThrownBy(() -> new ServiceClientProperties(null, "https://idp/token", "c", "s", Duration.ofSeconds(2))
                .validate("x")).hasMessage("x.base-url is required");
        assertThatThrownBy(() -> new ServiceClientProperties("ftp://hold", "https://idp/token", "c", "s",
                Duration.ofSeconds(2)).validate("x")).hasMessage("x.base-url must be an http or https URL");
        assertThatThrownBy(() -> new ServiceClientProperties("http://hold", "not a url", "c", "s",
                Duration.ofSeconds(2)).validate("x")).hasMessageStartingWith("x.token-uri");
        assertThatThrownBy(() -> new ServiceClientProperties("http://hold", "https://idp/token", " ", "s",
                Duration.ofSeconds(2)).validate("x")).hasMessage("x.client-id is required");
        assertThatThrownBy(() -> new ServiceClientProperties("http://hold", "https://idp/token", "c", "",
                Duration.ofSeconds(2)).validate("x")).hasMessage("x.client-secret is required");
        assertThatThrownBy(() -> new ServiceClientProperties("http://hold", "https://idp/token", "c", "s",
                Duration.ZERO).validate("x")).hasMessageStartingWith("x.timeout");
    }
}

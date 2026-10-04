package gov.itms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RemoteIamAuthorisationResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
    private static final String BODY = """
            {"subjectId":"u1","evaluatedAt":"2026-10-04T08:00:00Z","grants":[
              {"roleCode":"REFDATA_MAKER","permissions":["refdata.read","refdata.change.submit"],
               "scopes":{"regions":["R1"],"centres":["*"],"modules":["*"]},"validTo":null}]}""";

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private MockRestServiceServer server;
    private RemoteIamAuthorisationResolver resolver;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://iam");
        server = MockRestServiceServer.bindTo(builder).build();
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        resolver = new RemoteIamAuthorisationResolver(builder.build(), Duration.ofSeconds(60), 100, clock);
    }

    private static Jwt jwt(String token, String subject) {
        return Jwt.withTokenValue(token).header("alg", "none").subject(subject)
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(300)).build();
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: grants come from iam-service, forwarding the caller's token, and keep their scopes")
    void resolvesFromIam() {
        server.expect(requestTo("http://iam/v1/me/authorisation"))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        Authorisation a = resolver.resolve(jwt("tok-1", "u1"));

        assertThat(a.permissions()).containsExactly("refdata.change.submit", "refdata.read");
        assertThat(a.allows("refdata.read", ScopeTarget.region("R1"))).isTrue();
        assertThat(a.allows("refdata.read", ScopeTarget.region("R2"))).isFalse();
        assertThat(a.grants().get(0).approvalLimits()).as("absent field = no limits").isEmpty();
        server.verify();
    }

    @Test
    @Tag("CC-003")
    @DisplayName("CC-003: approval limits from iam-service are read per grant and apply within its scopes")
    void readsApprovalLimits() {
        server.expect(requestTo("http://iam/v1/me/authorisation")).andRespond(withSuccess("""
                {"subjectId":"u1","evaluatedAt":"2026-10-04T08:00:00Z","grants":[
                  {"roleCode":"FINANCE_OFFICER","permissions":["fin.refund.approve"],
                   "scopes":{"regions":["R1"],"centres":["*"],"modules":["*"]},"validTo":null,
                   "approvalLimits":[{"limitType":"REFUND_AMOUNT","maxAmountMinor":250000,"currency":"XTS"}]}]}""",
                MediaType.APPLICATION_JSON));

        Authorisation a = resolver.resolve(jwt("tok-limits", "u1"));

        assertThat(a.approvalLimit("REFUND_AMOUNT", "XTS", ScopeTarget.region("R1"))).hasValue(250_000);
        assertThat(a.approvalLimit("REFUND_AMOUNT", "XTS", ScopeTarget.region("R2"))).isEmpty();
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: results are cached per token for at most the configured TTL")
    void cachesUntilTtl() {
        server.expect(ExpectedCount.times(2), requestTo("http://iam/v1/me/authorisation"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        resolver.resolve(jwt("tok-1", "u1"));
        now.set(NOW.plusSeconds(59));
        resolver.resolve(jwt("tok-1", "u1")); // cached
        now.set(NOW.plusSeconds(61));
        resolver.resolve(jwt("tok-1", "u1")); // refreshed

        server.verify();
        assertThat(resolver.cacheSize()).isEqualTo(1);
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: if iam-service fails the resolver fails closed")
    void failsClosed() {
        server.expect(requestTo("http://iam/v1/me/authorisation")).andRespond(withServerError());

        assertThatThrownBy(() -> resolver.resolve(jwt("tok-2", "u1")))
                .isInstanceOf(AuthorisationUnavailableException.class);
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: an answer for a different subject is refused")
    void refusesOtherSubject() {
        server.expect(requestTo("http://iam/v1/me/authorisation"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> resolver.resolve(jwt("tok-3", "someone-else")))
                .isInstanceOf(AuthorisationUnavailableException.class);
    }
}

package gov.itms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

class AuthorisationFilterTest {

    private static final Jwt JWT = Jwt.withTokenValue("t").header("alg", "none").subject("u1")
            .claim("permissions", List.of("refdata.change.approve")).build();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate() {
        // Authorities on the incoming token must be ignored: only the resolver grants permissions.
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(JWT, List.of(new SimpleGrantedAuthority("refdata.change.approve"))));
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: the resolver's grants replace whatever the token carried")
    void replacesAuthentication() throws Exception {
        authenticate();
        Authorisation granted = new Authorisation("u1", List.of(new Authorisation.Grant("REFDATA_MAKER",
                Set.of("refdata.read"), Scopes.NATIONAL, null)), Instant.EPOCH);
        AtomicReference<Authentication> seen = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
        }) {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(SecurityContextHolder.getContext().getAuthentication());
            }
        };

        new AuthorisationFilter(jwt -> granted, new StaticMessageSource(), JsonMapper.builder().build())
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isInstanceOf(ItmsAuthentication.class);
        assertThat(seen.get().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("refdata.read");
        assertThat(((ItmsAuthentication) seen.get()).authorisation().roles()).containsExactly("REFDATA_MAKER");
    }

    @Test
    @Tag("CC-001")
    @DisplayName("CC-001: when grants cannot be resolved the request is refused with 503 (fail closed)")
    void failsClosedWith503() throws Exception {
        authenticate();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new AuthorisationFilter(jwt -> {
            throw new AuthorisationUnavailableException("down", null);
        }, new StaticMessageSource(), JsonMapper.builder().build())
                .doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"code\":\"AUTHORISATION_UNAVAILABLE\"");
        assertThat(chain.getRequest()).as("chain must not continue").isNull();
    }

    @Test
    @DisplayName("Anonymous requests pass through untouched (the authorisation rules then refuse them)")
    void anonymousPassesThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new AuthorisationFilter(jwt -> {
            throw new AssertionError("must not resolve");
        }, new StaticMessageSource(), JsonMapper.builder().build())
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}

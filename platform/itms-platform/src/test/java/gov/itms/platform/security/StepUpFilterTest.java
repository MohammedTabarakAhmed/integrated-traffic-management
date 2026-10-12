package gov.itms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

class StepUpFilterTest {

    private static final ItmsSecurityProperties.StepUp SETTINGS = new ItmsSecurityProperties.StepUp(
            List.of("otp"), List.of("POST /v1/me/payments", "PUT /v1/me/profile/**"));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signedIn(String subjectType, String acr) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u1");
        if (subjectType != null) {
            jwt.claim(SubjectType.CLAIM, subjectType);
        }
        if (acr != null) {
            jwt.claim("acr", acr);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build(), List.of()));
    }

    private static MockHttpServletResponse call(ItmsSecurityProperties.StepUp settings, String method, String path)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new StepUpFilter(settings, new StaticMessageSource(), JsonMapper.builder().build())
                .doFilter(request, response, chain);
        if (chain.getRequest() != null) {
            response.setStatus(299); // reached the endpoint
        }
        return response;
    }

    @Test
    @Tag("SEC-002")
    @DisplayName("SEC-002 (ADR-067): a customer needs acr=otp on a sensitive path; the 401 names the accepted acr "
            + "values (RFC 9470)")
    void customerNeedsStepUp() throws Exception {
        signedIn(SubjectType.CUSTOMER, "national-id");
        MockHttpServletResponse refused = call(SETTINGS, "POST", "/v1/me/payments");
        assertThat(refused.getStatus()).isEqualTo(401);
        assertThat(refused.getHeader("WWW-Authenticate")).contains("error=\"insufficient_user_authentication\"")
                .contains("acr_values=\"otp\"");
        assertThat(refused.getContentAsString()).contains("\"code\":\"STEP_UP_REQUIRED\"").contains("SEC-002");
        assertThat(call(SETTINGS, "PUT", "/v1/me/profile/mobile").getStatus()).isEqualTo(401);

        assertThat(call(SETTINGS, "GET", "/v1/me/payments").getStatus()).as("other method").isEqualTo(299);
        assertThat(call(SETTINGS, "POST", "/v1/me/violations").getStatus()).as("other path").isEqualTo(299);

        signedIn(SubjectType.CUSTOMER, "otp");
        assertThat(call(SETTINGS, "POST", "/v1/me/payments").getStatus()).isEqualTo(299);
        signedIn(SubjectType.CUSTOMER, null);
        assertThat(call(SETTINGS, "POST", "/v1/me/payments").getStatus()).as("no acr").isEqualTo(401);
    }

    @Test
    @Tag("SEC-002")
    @DisplayName("SEC-002 (ADR-067): staff tokens and the default (no sensitive paths) pass through")
    void staffAndDefaultsPass() throws Exception {
        signedIn(null, null);
        assertThat(call(SETTINGS, "POST", "/v1/me/payments").getStatus()).isEqualTo(299);
        signedIn(SubjectType.CUSTOMER, null);
        assertThat(call(new ItmsSecurityProperties.StepUp(List.of("otp"), List.of()), "POST", "/v1/me/payments")
                .getStatus()).isEqualTo(299);
    }
}

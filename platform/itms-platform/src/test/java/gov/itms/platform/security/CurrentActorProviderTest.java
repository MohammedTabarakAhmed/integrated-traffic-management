package gov.itms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentActorProviderTest {

    private final CurrentActorProvider provider = new CurrentActorProvider();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signedIn(String subjectType, String nationalId) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u1");
        if (subjectType != null) {
            jwt.claim(SubjectType.CLAIM, subjectType);
        }
        if (nationalId != null) {
            jwt.claim(SubjectType.NATIONAL_ID_CLAIM, nationalId);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build(), List.of()));
    }

    @Test
    @Tag("CH-001")
    @DisplayName("CH-001 (ADR-067 d): the customer and national id come from the token; the national id is not "
            + "printed")
    void customerFromToken() {
        signedIn(SubjectType.CUSTOMER, " 784199012345671 ");
        CurrentCustomer customer = provider.customer();
        assertThat(customer.subjectId()).isEqualTo("u1");
        assertThat(customer.nationalId()).isEqualTo("784199012345671");
        assertThat(customer.toString()).doesNotContain("784199012345671");
    }

    @Test
    @Tag("CH-001")
    @DisplayName("CH-001 (ADR-067 d): staff tokens and customer tokens without a national id are refused (403)")
    void othersRefused() {
        signedIn(null, "784199012345671");
        assertThatThrownBy(provider::customer).as("staff").isInstanceOf(AccessDeniedException.class);
        signedIn("STAFF", "784199012345671");
        assertThatThrownBy(provider::customer).as("other subject type").isInstanceOf(AccessDeniedException.class);
        signedIn(SubjectType.CUSTOMER, null);
        assertThatThrownBy(provider::customer).as("no national id").isInstanceOf(AccessDeniedException.class);
        signedIn(SubjectType.CUSTOMER, " ");
        assertThatThrownBy(provider::customer).as("blank national id").isInstanceOf(AccessDeniedException.class);
    }
}

package gov.itms.platform.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import io.micrometer.observation.ObservationRegistry;

class OwnTrafficFileTest {

    private MockRestServiceServer server;
    private OwnTrafficFile files;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://traffic-file");
        server = MockRestServiceServer.bindTo(builder).build();
        files = new OwnTrafficFile(builder.build());
        Jwt jwt = Jwt.withTokenValue("customer-token").header("alg", "none").subject("c1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Tag("CH-002")
    @DisplayName("ADR-067 d: the customer's own token is relayed and the file number comes back; 404 means no file")
    void relaysTheCustomerToken() {
        server.expect(requestTo("http://traffic-file/v1/me/traffic-file"))
                .andExpect(header("Authorization", "Bearer customer-token"))
                .andRespond(withSuccess("{\"fileNumber\":\"10000000017\",\"kind\":\"INDIVIDUAL\"}",
                        MediaType.APPLICATION_JSON));
        assertThat(files.fileNumber()).contains("10000000017");

        server.reset();
        server.expect(requestTo("http://traffic-file/v1/me/traffic-file")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(files.fileNumber()).isEmpty();
    }

    @Test
    @Tag("CH-002")
    @DisplayName("ADR-067 d: any other answer is a ServiceCallException; bad settings fail at start-up")
    void failures() {
        server.expect(requestTo("http://traffic-file/v1/me/traffic-file")).andRespond(withServerError());
        assertThatThrownBy(files::fileNumber).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(() -> OwnTrafficFile.create("ftp://x", Duration.ofSeconds(1), ObservationRegistry.NOOP))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OwnTrafficFile.create("http://x", Duration.ZERO, ObservationRegistry.NOOP))
                .isInstanceOf(IllegalStateException.class);
    }
}

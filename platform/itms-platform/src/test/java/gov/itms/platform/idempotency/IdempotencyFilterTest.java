package gov.itms.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Tag("FN-004")
class IdempotencyFilterTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
    private final IdempotencyFilter filter = new IdempotencyFilter(
            new IdempotencyKeys(store, Clock.systemUTC(), Duration.ofHours(1), Duration.ofSeconds(30)),
            List.of("/v1/things", "/v1/things/*/approvals"), new StaticMessageSource(), json);
    private final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void signIn() {
        signInAs("u1");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(String user) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, null, "x"));
    }

    private static MockHttpServletRequest post(String path, String key, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (key != null) {
            request.addHeader(IdempotencyFilter.HEADER, key);
        }
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** The "controller": echoes the body it reads and counts how often it ran. */
    private MockHttpServletResponse send(MockHttpServletRequest request, int status) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            runs.incrementAndGet();
            String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            HttpServletResponse http = (HttpServletResponse) res;
            http.setStatus(status);
            http.setContentType("application/json");
            http.setHeader("Location", "/v1/things/" + runs.get());
            http.getWriter().write("{\"echo\":" + body + "}");
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    private JsonNode problem(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentType()).startsWith("application/problem+json");
        return json.readTree(response.getContentAsString());
    }

    @Test
    void retryReplaysTheStoredResponseWithoutRunningAgain() throws Exception {
        MockHttpServletResponse first = send(post("/v1/things", "key-00001", "{\"a\":1}"), 201);
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getContentAsString()).isEqualTo("{\"echo\":{\"a\":1}}");
        assertThat(first.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isNull();

        MockHttpServletResponse retry = send(post("/v1/things", "key-00001", "{\"a\":1}"), 201);
        assertThat(runs).hasValue(1);
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(retry.getHeader("Location")).isEqualTo("/v1/things/1");
        assertThat(retry.getContentType()).startsWith("application/json");
        assertThat(retry.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
    }

    @Test
    void sameKeyWithAnotherBodyOrPathIsRefused() throws Exception {
        send(post("/v1/things", "key-00002", "{\"a\":1}"), 201);
        MockHttpServletResponse otherBody = send(post("/v1/things", "key-00002", "{\"a\":2}"), 201);
        assertThat(otherBody.getStatus()).isEqualTo(409);
        assertThat(problem(otherBody).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(problem(otherBody).get("requirementRefs").get(0).asString()).isEqualTo("FN-004");
        MockHttpServletResponse otherPath = send(post("/v1/things/7/approvals", "key-00002", "{\"a\":1}"), 200);
        assertThat(otherPath.getStatus()).isEqualTo(409);
        assertThat(runs).hasValue(1);
    }

    @Test
    void anotherCallerWithTheSameKeyIsNotGivenTheFirstCallersResponse() throws Exception {
        send(post("/v1/things", "key-00003", "{\"a\":1}"), 201);
        signInAs("u2");
        MockHttpServletResponse other = send(post("/v1/things", "key-00003", "{\"a\":1}"), 201);
        assertThat(other.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isNull();
        assertThat(runs).hasValue(2);
    }

    @Test
    void missingOrUnsafeKeyIsRefusedBeforeAnythingRuns() throws Exception {
        MockHttpServletResponse missing = send(post("/v1/things", null, "{}"), 201);
        assertThat(missing.getStatus()).isEqualTo(400);
        assertThat(problem(missing).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        MockHttpServletResponse blank = send(post("/v1/things", " ", "{}"), 201);
        assertThat(problem(blank).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        for (String bad : List.of("short", "has space-123", "x".repeat(129), "bad\nline-123")) {
            MockHttpServletResponse invalid = send(post("/v1/things", bad, "{}"), 201);
            assertThat(invalid.getStatus()).as(bad).isEqualTo(400);
            assertThat(problem(invalid).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_INVALID");
        }
        assertThat(runs).hasValue(0);
        assertThat(store.rows).isEmpty();
    }

    @Test
    void failedRequestReleasesTheKeySoItCanBeRetried() throws Exception {
        assertThat(send(post("/v1/things", "key-00004", "{\"a\":1}"), 422).getStatus()).isEqualTo(422);
        assertThat(store.rows).isEmpty();
        assertThat(send(post("/v1/things", "key-00004", "{\"a\":1}"), 201).getStatus()).isEqualTo(201);
        assertThat(runs).hasValue(2);
    }

    @Test
    void exceptionReleasesTheKeyAndPropagates() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };
        assertThatThrownBy(() -> filter.doFilter(post("/v1/things", "key-00005", "{}"), new MockHttpServletResponse(),
                failing)).isInstanceOf(IllegalStateException.class);
        assertThat(store.rows).isEmpty();
    }

    @Test
    void requestStillRunningAnswersInProgress() throws Exception {
        MockHttpServletResponse[] inner = new MockHttpServletResponse[1];
        FilterChain reentrant = (req, res) -> {
            try {
                inner[0] = send(post("/v1/things", "key-00006", "{}"), 201);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            ((HttpServletResponse) res).setStatus(201);
        };
        filter.doFilter(post("/v1/things", "key-00006", "{}"), new MockHttpServletResponse(), reentrant);
        assertThat(inner[0].getStatus()).isEqualTo(409);
        assertThat(inner[0].getHeader("Retry-After")).isEqualTo("2");
        assertThat(problem(inner[0]).get("code").asString()).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS");
    }

    @Test
    void otherMethodsPathsAndUnauthenticatedRequestsPassThrough() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/v1/things");
        assertThat(filter.shouldNotFilter(get)).isTrue();
        assertThat(filter.shouldNotFilter(post("/v1/other", null, "{}"))).isTrue();
        assertThat(filter.shouldNotFilter(post("/v1/things/9/approvals", null, "{}"))).isFalse();
        SecurityContextHolder.clearContext();
        assertThat(send(post("/v1/things", null, "{}"), 201).getStatus()).isEqualTo(201);
        assertThat(store.rows).isEmpty();
    }

    @Test
    void hashCoversMethodPathQueryAndBody() {
        MockHttpServletRequest a = post("/v1/things", "k", "{}");
        MockHttpServletRequest b = post("/v1/things", "k", "{}");
        b.setQueryString("x=1");
        assertThat(IdempotencyFilter.hash(a, new byte[0])).isEqualTo(IdempotencyFilter.hash(a, new byte[0]))
                .isNotEqualTo(IdempotencyFilter.hash(b, new byte[0]))
                .isNotEqualTo(IdempotencyFilter.hash(a, new byte[] {1}))
                .hasSize(64);
    }
}

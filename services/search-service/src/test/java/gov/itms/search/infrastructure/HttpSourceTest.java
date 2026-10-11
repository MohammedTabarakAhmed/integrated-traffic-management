package gov.itms.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.search.application.Ports;
import gov.itms.search.domain.Search;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("CC-100")
class HttpSourceTest {

    private static final SourceStub STUB = new SourceStub();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Ports.Caller CALLER = new Ports.Caller("u-1", "COUNTER", "user-token");

    @AfterAll
    static void stop() {
        STUB.close();
    }

    private static HttpSource source(String name, String method, String path, String body, String items,
                                     Duration timeout) {
        return new HttpSource(new SearchProperties.SourceSettings(name, Search.EntityType.TRAFFIC_FILE,
                URI.create(STUB.baseUrl()), method, path, body, "^.+$", items, "fileNumber", null, "regionOpened",
                List.of("status", "identity.name.givenEn", "missing", "identity"), timeout), JSON);
    }

    @Test
    @Tag("CC-102")
    @DisplayName("CC-100, CC-102: asked with the caller's own token and channel; the query is URL-encoded; fields are "
            + "read by dotted path and only scalars are kept")
    void get() {
        STUB.answers.put("/v1/traffic-files/S%206", new SourceStub.Answer(200, "{\"fileNumber\":\"TF-9\","
                + "\"regionOpened\":\"DXB\",\"status\":\"ACTIVE\",\"identity\":{\"name\":{\"givenEn\":\"Mo\"}}}", 0));
        HttpSource s = source("tf-get", "GET", "/v1/traffic-files/{q}", null, null, Duration.ofSeconds(2));
        assertThat(s.accepts("x")).isTrue();
        assertThat(s.find("S 6", CALLER)).containsExactly(new Search.Hit(Search.EntityType.TRAFFIC_FILE, "TF-9",
                "TF-9", "DXB", Map.of("status", "ACTIVE", "identity.name.givenEn", "Mo")));
        SourceStub.Seen seen = STUB.seen.stream().filter(r -> r.target().contains("S%206")).findFirst().orElseThrow();
        assertThat(seen.authorization()).isEqualTo("Bearer user-token");
        assertThat(seen.channel()).isEqualTo("COUNTER");
    }

    @Test
    @DisplayName("CC-100: a POST source gets the query as a JSON string in its body; collection answers are read")
    void post() throws Exception {
        STUB.answers.put("/v1/traffic-files/searches", new SourceStub.Answer(200,
                "{\"items\":[{\"fileNumber\":\"TF-1\"},{\"noId\":true}]}", 0));
        HttpSource s = source("tf-post", "POST", "/v1/traffic-files/searches",
                "{\"document\":{\"type\":\"NATIONAL_ID\",\"nationalIdNo\":{q}}}", "items", Duration.ofSeconds(2));
        assertThat(s.find("784\"}", CALLER)).extracting(Search.Hit::id).containsExactly("TF-1");
        String body = STUB.seen.stream().filter(r -> r.method().equals("POST")).findFirst().orElseThrow().body();
        assertThat(JSON.readTree(body).get("document").get("nationalIdNo").asString()).isEqualTo("784\"}");
    }

    @Test
    @DisplayName("CC-100: 404, 400 and 422 are no hit; 401 and 403 are FORBIDDEN; 5xx, timeouts and bad answers are "
            + "UNAVAILABLE")
    void answers() {
        HttpSource s = source("tf-status", "GET", "/v1/status/{q}", null, null, Duration.ofMillis(300));
        assertThat(s.find("none", CALLER)).isEmpty();
        STUB.answers.put("/v1/status/bad", new SourceStub.Answer(422, "{}", 0));
        assertThat(s.find("bad", CALLER)).isEmpty();
        STUB.answers.put("/v1/status/no", new SourceStub.Answer(403, "{}", 0));
        assertThatThrownBy(() -> s.find("no", CALLER)).isInstanceOf(Ports.Forbidden.class);
        STUB.answers.put("/v1/status/down", new SourceStub.Answer(503, "{}", 0));
        assertThatThrownBy(() -> s.find("down", CALLER)).isInstanceOf(Ports.Unavailable.class);
        STUB.answers.put("/v1/status/slow", new SourceStub.Answer(200, "{\"fileNumber\":\"X\"}", 2_000));
        assertThatThrownBy(() -> s.find("slow", CALLER)).isInstanceOf(Ports.Unavailable.class);
        STUB.answers.put("/v1/status/garbage", new SourceStub.Answer(200, "not json", 0));
        assertThatThrownBy(() -> s.find("garbage", CALLER)).isInstanceOf(Ports.Unavailable.class);
        HttpSource items = source("tf-items", "GET", "/v1/status/{q}", null, "items", Duration.ofSeconds(2));
        STUB.answers.put("/v1/status/noitems", new SourceStub.Answer(200, "{\"fileNumber\":\"X\"}", 0));
        assertThatThrownBy(() -> items.find("noitems", CALLER)).isInstanceOf(Ports.Unavailable.class);
        assertThat(items.hits("")).isEmpty();
    }

    @Test
    @DisplayName("NFR-042: source settings are checked at start-up")
    void settings() {
        URI base = URI.create("http://localhost:1");
        assertThatThrownBy(() -> new SearchProperties.SourceSettings("xx", Search.EntityType.VEHICLE, base, "GET",
                "/v1/x/{q}", null, "[", null, "id", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("regex");
        assertThatThrownBy(() -> new SearchProperties.SourceSettings("xx", Search.EntityType.VEHICLE, base, "POST",
                "/v1/x", null, ".*", null, "id", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchProperties.SourceSettings("xx", Search.EntityType.VEHICLE,
                URI.create("file:///etc"), "GET", "/v1/x", null, ".*", null, "id", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchProperties.SourceSettings("xx", Search.EntityType.VEHICLE, base, "GET",
                "/v1/x", null, ".*", null, "id", null, null, null, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        SearchProperties.SourceSettings ok = new SearchProperties.SourceSettings("xx", Search.EntityType.VEHICLE, base,
                "get", "/v1/x", null, ".*", null, "id", null, null, null, null);
        assertThat(ok.method()).isEqualTo("GET");
        assertThat(ok.timeout()).isEqualTo(Duration.ofMillis(1500));
        assertThatThrownBy(() -> new SearchProperties(Duration.ofMillis(10), 50, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchProperties(Duration.ofSeconds(2), 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchProperties(Duration.ofSeconds(2), 5, List.of(ok, ok), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SearchProperties(Duration.ofSeconds(2), 5, null, null).sources()).isEmpty();
        SearchProperties.SourceSettings other = new SearchProperties.SourceSettings("yy", Search.EntityType.VEHICLE,
                base, "GET", "/v1/y", null, ".*", null, "id", null, null, null, null);
        assertThat(new SearchProperties(Duration.ofSeconds(2), 5, List.of(ok, other), List.of("xx")).enabledSources())
                .containsExactly(other);
    }
}

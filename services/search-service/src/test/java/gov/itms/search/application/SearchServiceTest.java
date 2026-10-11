package gov.itms.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.search.domain.DomainException;
import gov.itms.search.domain.Search;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-100")
class SearchServiceTest {

    private static final Ports.Caller CALLER = new Ports.Caller("u-1", "BACKOFFICE", "tok");

    private final List<Map<String, Object>> audited = new ArrayList<>();
    private final List<String> asked = new CopyOnWriteArrayList<>();

    private final AuditRecorder audit = new AuditRecorder() {
        @Override
        @SuppressWarnings("unchecked")
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            assertThat(action).isEqualTo("SEARCH_PERFORMED");
            audited.add((Map<String, Object>) newValue);
        }

        @Override
        public void recordAs(CurrentActor actor, String action, String entityType, String entityId, Object oldValue,
                             Object newValue, String reason) {
            record(action, entityType, entityId, oldValue, newValue, reason);
        }
    };

    private Ports.Source source(String name, Search.EntityType type, String pattern,
                                Function<String, List<Search.Hit>> answer) {
        return new Ports.Source() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Search.EntityType type() {
                return type;
            }

            @Override
            public boolean accepts(String query) {
                return query.matches(pattern);
            }

            @Override
            public List<Search.Hit> find(String query, Ports.Caller caller) {
                assertThat(caller).isSameAs(CALLER);
                asked.add(name);
                return answer.apply(query);
            }
        };
    }

    private static Search.Hit hit(Search.EntityType type, String id) {
        return new Search.Hit(type, id, id, "DXB", Map.of("status", "ACTIVE"));
    }

    @Test
    @Tag("CC-102")
    @DisplayName("CC-100, CC-102: only sources whose shape matches are asked; refusals and outages are reported, not "
            + "hidden; duplicates removed; the search is audited")
    void federated() {
        SearchService search = new SearchService(List.of(
                source("file", Search.EntityType.TRAFFIC_FILE, "TF-.*",
                        q -> List.of(hit(Search.EntityType.TRAFFIC_FILE, q), hit(Search.EntityType.TRAFFIC_FILE, q))),
                source("file-identity", Search.EntityType.TRAFFIC_FILE, "[0-9]+", q -> List.of()),
                source("vehicle", Search.EntityType.VEHICLE, ".*", q -> {
                    throw new Ports.Forbidden("no vehicle.read");
                }),
                source("violation", Search.EntityType.VIOLATION, ".*", q -> {
                    throw new Ports.Unavailable("down", null);
                })), audit, Duration.ofSeconds(2), 50);

        Search.Result r = search.search(new Search.Query("  TF-1  ", null), CALLER);

        assertThat(asked).containsExactlyInAnyOrder("file", "vehicle", "violation");
        assertThat(r.items()).containsExactly(hit(Search.EntityType.TRAFFIC_FILE, "TF-1"));
        assertThat(r.sources()).extracting(Search.Source::name, Search.Source::status).containsExactly(
                org.assertj.core.groups.Tuple.tuple("file", Search.SourceStatus.OK),
                org.assertj.core.groups.Tuple.tuple("vehicle", Search.SourceStatus.FORBIDDEN),
                org.assertj.core.groups.Tuple.tuple("violation", Search.SourceStatus.UNAVAILABLE));
        assertThat(audited).singleElement().satisfies(a -> {
            assertThat(a).containsEntry("query", "TF-1").containsEntry("hits", 1).containsEntry("channel",
                    "BACKOFFICE");
            assertThat(a.get("sources")).isEqualTo(List.of("file:OK", "vehicle:FORBIDDEN", "violation:UNAVAILABLE"));
        });
    }

    @Test
    @Tag("CC-103")
    @DisplayName("CC-103: a source slower than the deadline is UNAVAILABLE and does not hold the answer back")
    void deadline() {
        SearchService search = new SearchService(List.of(
                source("slow", Search.EntityType.ACCIDENT, ".*", q -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of(hit(Search.EntityType.ACCIDENT, "A"));
                }),
                source("fast", Search.EntityType.VIOLATION, ".*", q -> List.of(hit(Search.EntityType.VIOLATION, q)))),
                audit, Duration.ofMillis(300), 50);
        long start = System.nanoTime();
        Search.Result r = search.search(new Search.Query("V-2026-1", null), CALLER);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(r.items()).extracting(Search.Hit::id).containsExactly("V-2026-1");
        assertThat(r.sources()).extracting(Search.Source::status)
                .containsExactly(Search.SourceStatus.UNAVAILABLE, Search.SourceStatus.OK);
    }

    @Test
    @DisplayName("CC-100: types limit the sources asked; the number of hits is capped")
    void typesAndCap() {
        SearchService search = new SearchService(List.of(
                source("file", Search.EntityType.TRAFFIC_FILE, ".*", q -> List.of(
                        hit(Search.EntityType.TRAFFIC_FILE, "1"), hit(Search.EntityType.TRAFFIC_FILE, "2"),
                        hit(Search.EntityType.TRAFFIC_FILE, "3"))),
                source("vehicle", Search.EntityType.VEHICLE, ".*", q -> List.of(hit(Search.EntityType.VEHICLE, q)))),
                audit, Duration.ofSeconds(2), 2);
        Search.Result r = search.search(new Search.Query("ABC", Set.of(Search.EntityType.TRAFFIC_FILE)), CALLER);
        assertThat(asked).containsExactly("file");
        assertThat(r.items()).hasSize(2);
        assertThat(audited.getFirst()).containsEntry("hits", 3).containsEntry("types", List.of("TRAFFIC_FILE"));
    }

    @Test
    @DisplayName("CC-100: a query must be 2-100 letters, digits, spaces or . _ : / -")
    void queryRules() {
        for (String bad : new String[] {null, "", " a ", "x".repeat(101), "a;drop", "a'b", "a%b"}) {
            assertThatThrownBy(() -> new Search.Query(bad, null)).as(String.valueOf(bad))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("QUERY_INVALID");
        }
        assertThat(new Search.Query("محمد أحمد", null).types()).containsExactlyInAnyOrder(Search.EntityType.values());
        assertThat(new Search.Query("S 6", Set.of()).text()).isEqualTo("S 6");
        assertThat(CALLER.toString()).doesNotContain("tok");
    }
}

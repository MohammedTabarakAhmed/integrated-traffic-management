package gov.itms.search.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.search.domain.Search;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified staff search (CC-100, CC-102). One query goes to every configured source whose identifier shape it has
 * (file number, identity document, VIN, plate, licence, violation, accident, transaction), in parallel and with the
 * caller's own token: each owning service decides what this caller may see and audits the read, so search-service
 * holds no copy of anyone's data (one database per service). A source that refuses the caller or does not answer is
 * reported, never hidden. Every search is recorded in the central audit trail with its query and outcome (CC-102).
 */
public class SearchService {

    private static final Logger LOG = LoggerFactory.getLogger(SearchService.class);
    public static final String EXECUTE = "search.execute";

    private final List<Ports.Source> sources;
    private final AuditRecorder audit;
    private final Duration deadline;
    private final int maxHits;

    public SearchService(List<Ports.Source> sources, AuditRecorder audit, Duration deadline, int maxHits) {
        this.sources = List.copyOf(sources);
        this.audit = audit;
        this.deadline = deadline;
        this.maxHits = maxHits;
    }

    public Search.Result search(Search.Query query, Ports.Caller caller) {
        List<Ports.Source> asked = sources.stream()
                .filter(s -> query.types().contains(s.type()) && s.accepts(query.text())).toList();
        Map<Ports.Source, Future<List<Search.Hit>>> running = new LinkedHashMap<>();
        List<Search.Hit> hits = new ArrayList<>();
        List<Search.Source> answered = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Ports.Source s : asked) {
                running.put(s, pool.submit(() -> s.find(query.text(), caller)));
            }
            long until = System.nanoTime() + deadline.toNanos();
            for (Map.Entry<Ports.Source, Future<List<Search.Hit>>> e : running.entrySet()) {
                Ports.Source s = e.getKey();
                Search.SourceStatus status;
                try {
                    hits.addAll(e.getValue().get(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS));
                    status = Search.SourceStatus.OK;
                } catch (ExecutionException ex) {
                    status = ex.getCause() instanceof Ports.Forbidden ? Search.SourceStatus.FORBIDDEN
                            : Search.SourceStatus.UNAVAILABLE;
                    if (status == Search.SourceStatus.UNAVAILABLE) {
                        LOG.warn("Search source {} unavailable: {}", s.name(), ex.getCause().getClass().getSimpleName());
                    }
                } catch (TimeoutException ex) {
                    e.getValue().cancel(true);
                    status = Search.SourceStatus.UNAVAILABLE;
                    LOG.warn("Search source {} did not answer within {}", s.name(), deadline);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    status = Search.SourceStatus.UNAVAILABLE;
                }
                answered.add(new Search.Source(s.name(), s.type(), status));
            }
            pool.shutdownNow();
        }
        List<Search.Hit> unique = distinct(hits);
        String id = UUID.randomUUID().toString();
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("query", query.text());
        outcome.put("types", query.types().stream().map(Enum::name).sorted().toList());
        outcome.put("hits", unique.size());
        outcome.put("sources", answered.stream().map(s -> s.name() + ":" + s.status()).toList());
        outcome.put("channel", caller.channel());
        audit.record("SEARCH_PERFORMED", "Search", id, null, outcome, null);
        return new Search.Result(id, unique.size() > maxHits ? unique.subList(0, maxHits) : unique, answered);
    }

    private static List<Search.Hit> distinct(List<Search.Hit> hits) {
        Set<String> seen = new LinkedHashSet<>();
        List<Search.Hit> out = new ArrayList<>();
        for (Search.Hit h : hits) {
            if (seen.add(h.type() + "/" + h.id())) {
                out.add(h);
            }
        }
        return out;
    }
}

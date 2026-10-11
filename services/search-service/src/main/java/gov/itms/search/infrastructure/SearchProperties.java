package gov.itms.search.infrastructure;

import gov.itms.search.domain.Search;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Search settings, {@code itms.search.*} (NFR-042): the overall deadline (CC-103 asks 2 s for simple searches), the
 * most hits returned, and the sources: which owning service answers which identifier shape, and where its answer
 * keeps the id, reference, region and the fields shown. {@code disabledSources} switches named sources off (for example
 * where the owning service is not deployed) without editing the list.
 */
@ConfigurationProperties("itms.search")
public record SearchProperties(@DefaultValue("2s") Duration deadline, @DefaultValue("50") int maxHits,
                               List<SourceSettings> sources, List<String> disabledSources) {

    public SearchProperties {
        if (deadline.compareTo(Duration.ofMillis(200)) < 0 || deadline.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("itms.search.deadline must be 200 ms to 30 s (NFR-042)");
        }
        if (maxHits < 1 || maxHits > 500) {
            throw new IllegalArgumentException("itms.search.max-hits must be 1-500 (NFR-042)");
        }
        sources = sources == null ? List.of() : List.copyOf(sources);
        if (sources.size() > 30 || new HashSet<>(sources.stream().map(SourceSettings::name).toList()).size()
                != sources.size()) {
            throw new IllegalArgumentException("itms.search.sources: at most 30, with distinct names");
        }
        disabledSources = disabledSources == null ? List.of() : List.copyOf(disabledSources);
    }

    /** The sources in use: every configured one not switched off. */
    public List<SourceSettings> enabledSources() {
        return sources.stream().filter(s -> !disabledSources.contains(s.name())).toList();
    }

    /**
     * One source.
     *
     * @param path       request path with {@code {q}} for the query (URL-encoded)
     * @param body       for POST: a JSON template with {@code {q}} where the query goes as a JSON string
     * @param itemsField the array holding the records in a collection answer; null when the answer is one record
     */
    public record SourceSettings(String name, Search.EntityType type, URI baseUrl, @DefaultValue("GET") String method,
                                 String path, String body, String pattern, String itemsField, String idField,
                                 String referenceField, String regionField, List<String> fields,
                                 @DefaultValue("1500ms") Duration timeout) {
        public SourceSettings {
            if (name == null || !name.matches("^[a-z][a-z0-9-]{1,39}$") || type == null || baseUrl == null
                    || path == null || !path.startsWith("/") || idField == null || pattern == null) {
                throw new IllegalArgumentException("itms.search.sources: name, type, base-url, path, id-field and "
                        + "pattern are required (" + name + ")");
            }
            if (!"http".equals(baseUrl.getScheme()) && !"https".equals(baseUrl.getScheme())) {
                throw new IllegalArgumentException("itms.search.sources." + name + ".base-url must be http(s)");
            }
            method = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !(method.equals("POST") && body != null)) {
                throw new IllegalArgumentException("itms.search.sources." + name + ": GET, or POST with a body");
            }
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("itms.search.sources." + name + ".pattern is not a regex", e);
            }
            fields = fields == null ? List.of() : List.copyOf(fields);
            timeout = timeout == null ? Duration.ofMillis(1500) : timeout;
            if (timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("itms.search.sources." + name + ".timeout must be 100 ms to 30 s");
            }
        }
    }
}

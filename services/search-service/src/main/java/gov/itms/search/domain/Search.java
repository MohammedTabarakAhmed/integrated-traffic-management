package gov.itms.search.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The values of a unified search (CC-100): the query, the record types, the hits and how each source answered. */
public final class Search {

    private Search() {
    }

    /** The record types of CC-100: people, companies, licences, vehicles, plates, violations, accidents, transactions. */
    public enum EntityType { TRAFFIC_FILE, LICENCE, VEHICLE, VIOLATION, ACCIDENT, TRANSACTION }

    /** How a source answered: hits or none, out of the caller's permission or scope, or not reachable. */
    public enum SourceStatus { OK, FORBIDDEN, UNAVAILABLE }

    private static final Pattern QUERY = Pattern.compile("^[\\p{L}\\p{N} ._:/-]{2,100}$");

    /**
     * A query: 2-100 letters, digits, spaces and {@code . _ : / -}. Leading and trailing blanks are dropped; nothing
     * else is changed, because identifiers are matched exactly by the owning services.
     */
    public record Query(String text, Set<EntityType> types) {
        public Query {
            text = text == null ? null : text.strip();
            if (text == null || !QUERY.matcher(text).matches()) {
                throw new DomainException(DomainException.Kind.VALIDATION, "QUERY_INVALID",
                        "give 2 to 100 letters, digits, spaces or . _ : / -", List.of("CC-100"));
            }
            types = types == null || types.isEmpty() ? Set.of(EntityType.values()) : Set.copyOf(types);
        }
    }

    /** One record found, as its owning service showed it to this caller. */
    public record Hit(EntityType type, String id, String reference, String region, Map<String, String> fields) {
        public Hit {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }

    /** How one source answered. */
    public record Source(String name, EntityType type, SourceStatus status) {
    }

    /** The answer: hits from every source that answered and the status of each source asked. */
    public record Result(String searchId, List<Hit> items, List<Source> sources) {
        public Result {
            items = List.copyOf(items);
            sources = List.copyOf(sources);
        }
    }
}

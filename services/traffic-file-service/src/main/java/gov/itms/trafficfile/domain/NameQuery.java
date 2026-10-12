package gov.itms.trafficfile.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A name search (CC-101; ADR-063): 2-100 characters of letters (any script), spaces and {@code . ' -}, with at least
 * two letters; optionally only people or only companies; at most {@code limit} files. How names match (partial, both
 * languages, transliterated, phonetic) is decided by the database's name keys (V6), not here.
 */
public record NameQuery(String text, TrafficFile.Kind kind, int limit) {

    private static final Pattern TEXT = Pattern.compile("^[\\p{L}\\p{M} .'-]{2,100}$");

    /** How a file's name matched, best first. */
    public enum Match { EXACT, PARTIAL, PHONETIC }

    public NameQuery {
        text = text == null ? null : text.strip();
        if (text == null || !TEXT.matcher(text).matches() || text.codePoints().filter(Character::isLetter).count() < 2) {
            throw DomainException.validation("NAME_QUERY_INVALID",
                    "give 2 to 100 letters, spaces, dots, apostrophes or hyphens (at least two letters)",
                    List.of("CC-101"), "name");
        }
        if (limit < 1) {
            throw DomainException.validation("FIELD_INVALID", "limit must be at least 1", List.of("CC-101"), "limit");
        }
    }
}

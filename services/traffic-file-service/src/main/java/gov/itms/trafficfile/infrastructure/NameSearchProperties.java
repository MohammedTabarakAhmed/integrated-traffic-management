package gov.itms.trafficfile.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Name search settings (CC-101; ADR-063).
 *
 * @param maxResults   most files one search shows, 1-100 (a caller asking for more gets this many)
 * @param minKeyLength letters a query word's phonetic key needs before the word is matched by sound, 2-6; shorter words
 *                     ("Ali" gives "l") must match as text
 */
@ConfigurationProperties("itms.traffic-file.name-search")
public record NameSearchProperties(@DefaultValue("20") int maxResults, @DefaultValue("3") int minKeyLength) {

    public NameSearchProperties {
        if (maxResults < 1 || maxResults > 100) {
            throw new IllegalArgumentException("itms.traffic-file.name-search.max-results must be 1..100");
        }
        if (minKeyLength < 2 || minKeyLength > 6) {
            throw new IllegalArgumentException("itms.traffic-file.name-search.min-key-length must be 2..6");
        }
    }
}

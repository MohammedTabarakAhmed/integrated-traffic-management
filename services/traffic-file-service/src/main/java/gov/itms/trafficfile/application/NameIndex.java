package gov.itms.trafficfile.application;

import gov.itms.trafficfile.domain.NameQuery;
import gov.itms.trafficfile.domain.TrafficFile;
import java.util.List;

/** Files found by name (CC-101; ADR-063); merged-away files are left out. */
public interface NameIndex {

    /**
     * The files whose name matches, best match first, at most {@code query.limit()}.
     *
     * @param minKeyLength a query word is matched by sound only when its phonetic key has at least this many letters
     */
    List<Hit> find(NameQuery query, int minKeyLength);

    /** A file found by name: what the caller needs to pick it, no identity numbers. */
    record Hit(String fileNumber, TrafficFile.Kind kind, TrafficFile.Status status, String regionOpened,
               NameQuery.Match match, String nameNl, String nameEn) {
    }
}

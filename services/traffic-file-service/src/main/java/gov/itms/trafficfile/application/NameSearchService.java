package gov.itms.trafficfile.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.trafficfile.domain.NameQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds people's and companies' files by name (CC-101; ADR-063): partial matches in either language, names written in
 * the other script (transliteration) and spelling variants (phonetic), best match first. Needs
 * {@value TrafficFileService#READ}; files are national for staff (Q-057). Every search is audited, found or not, with
 * the files it showed but not the name searched for (CC-102, as the identity search).
 */
public class NameSearchService {

    private final NameIndex index;
    private final AuditRecorder audit;
    private final int maxResults;
    private final int minKeyLength;

    public NameSearchService(NameIndex index, AuditRecorder audit, int maxResults, int minKeyLength) {
        this.index = index;
        this.audit = audit;
        this.maxResults = maxResults;
        this.minKeyLength = minKeyLength;
    }

    @Transactional
    public List<NameIndex.Hit> search(NameQuery query, Caller caller) {
        TrafficFileService.requireRead(caller);
        NameQuery capped = query.limit() > maxResults ? new NameQuery(query.text(), query.kind(), maxResults) : query;
        List<NameIndex.Hit> hits = index.find(capped, minKeyLength);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", capped.kind() == null ? null : capped.kind().name());
        result.put("found", hits.stream().map(NameIndex.Hit::fileNumber).toList());
        audit.record("TRAFFIC_FILE_NAME_SEARCHED", TrafficFileService.ENTITY, "-", null, result, null);
        return hits;
    }
}

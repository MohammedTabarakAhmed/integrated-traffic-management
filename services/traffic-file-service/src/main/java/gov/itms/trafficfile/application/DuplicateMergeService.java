package gov.itms.trafficfile.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.DomainException;
import gov.itms.trafficfile.domain.NameSimilarity;
import gov.itms.trafficfile.domain.TrafficFile;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Probable duplicate files and merging (DL-009; ADR-044):
 * <ul>
 *   <li>an individual's probable duplicates are the other active individuals' files with the same birth date and
 *       nationality whose names are similar enough in either language ({@link NameSimilarity}, threshold in
 *       configuration). Identical identity numbers cannot occur: one file per document is enforced at opening;</li>
 *   <li>merging needs {@value #MERGE} (any grant; a supervisor's decision) and a reason. The merged file becomes
 *       MERGED and points to the surviving file; both stay readable. Other services move licences, vehicles,
 *       violations and history to the surviving file when they receive {@code itms.traffic-file.merged}
 *       (INT-005); this service keeps the merge record;</li>
 *   <li>views and merges are audited (CC-120).</li>
 * </ul>
 */
public class DuplicateMergeService {

    public static final String MERGE = "trafficfile.merge";
    static final String EVENT_MERGED = "itms.traffic-file.merged";

    private final TrafficFileRepository files;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;
    private final double threshold;

    public DuplicateMergeService(TrafficFileRepository files, AuditRecorder audit, DomainEventRecorder events,
                                 Clock clock, double threshold) {
        if (threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("duplicate threshold must be in (0, 1]");
        }
        this.files = files;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.threshold = threshold;
    }

    /** A probable duplicate and how similar the names are (0..1). */
    public record Candidate(TrafficFile file, double nameSimilarity) {
    }

    /** One merge, as recorded. */
    public record Merge(UUID id, String mergedFileNumber, String survivingFileNumber, String reason, String actorId,
                        Channel channel, Instant at) {
    }

    @Transactional
    public List<Candidate> probableDuplicates(String fileNumber, Caller caller) {
        TrafficFileService.requireRead(caller);
        TrafficFile file = files.findByNumber(fileNumber).orElseThrow(() -> TrafficFileService.notFound(fileNumber));
        if (file.kind() != TrafficFile.Kind.INDIVIDUAL) {
            throw DomainException.validation("WRONG_FILE_KIND", "duplicates are detected for individuals' files",
                    List.of("DL-009"), fileNumber, file.kind().name());
        }
        List<Candidate> candidates = files.findDuplicateCandidates(file.identity().birthDate(),
                        file.identity().nationality(), file.id()).stream()
                .map(c -> new Candidate(c, NameSimilarity.of(file.identity().name(), c.identity().name())))
                .filter(c -> c.nameSimilarity() >= threshold)
                .sorted(Comparator.comparingDouble(Candidate::nameSimilarity).reversed())
                .toList();
        audit.record("TRAFFIC_FILE_DUPLICATES_VIEWED", TrafficFileService.ENTITY, fileNumber, null,
                Map.of("count", candidates.size()), null);
        return candidates;
    }

    @Transactional
    public Merge merge(String survivingNumber, String mergedNumber, String reason, Caller caller) {
        Channel channel = TrafficFileService.channelOf(caller);
        if (!caller.grants().holds(MERGE)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not merge traffic files", List.of("CC-001", "DL-009"));
        }
        String checkedReason = reason == null ? "" : reason.strip();
        if (checkedReason.length() < 3 || checkedReason.length() > 500) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("DL-009"));
        }
        // Lock both rows in a fixed order, so two crossing merges cannot deadlock.
        boolean survivorFirst = survivingNumber.compareTo(mergedNumber) < 0;
        TrafficFile first = files.findByNumberForUpdate(survivorFirst ? survivingNumber : mergedNumber)
                .orElseThrow(() -> TrafficFileService.notFound(survivorFirst ? survivingNumber : mergedNumber));
        TrafficFile second = files.findByNumberForUpdate(survivorFirst ? mergedNumber : survivingNumber)
                .orElseThrow(() -> TrafficFileService.notFound(survivorFirst ? mergedNumber : survivingNumber));
        TrafficFile survivor = survivorFirst ? first : second;
        TrafficFile merged = survivorFirst ? second : first;

        Instant now = clock.instant();
        merged.mergeInto(survivor, caller.actor(), channel, now);
        files.saveMerge(merged);
        Merge record = new Merge(UUID.randomUUID(), merged.fileNumber(), survivor.fileNumber(), checkedReason,
                caller.actor().id(), channel, now);
        files.insertMerge(record, merged.id(), survivor.id());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mergeId", record.id().toString());
        data.put("mergedFileNumber", merged.fileNumber());
        data.put("survivingFileNumber", survivor.fileNumber());
        data.put("kind", merged.kind().name());
        audit.record("TRAFFIC_FILE_MERGED", TrafficFileService.ENTITY, merged.fileNumber(), null, data, checkedReason);
        audit.record("TRAFFIC_FILE_MERGE_RECEIVED", TrafficFileService.ENTITY, survivor.fileNumber(), null, data,
                checkedReason);
        events.record(EVENT_MERGED, merged.fileNumber(), data);
        return record;
    }
}

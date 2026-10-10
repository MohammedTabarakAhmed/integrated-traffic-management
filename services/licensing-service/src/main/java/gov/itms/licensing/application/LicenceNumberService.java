package gov.itms.licensing.application;

import gov.itms.licensing.application.LicenceNumberRepository.UsedNumber;
import gov.itms.licensing.domain.Channel;
import gov.itms.licensing.domain.DomainException;
import gov.itms.licensing.domain.LicenceNumberRange;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Licence number ranges per region (DL-061):
 * <ul>
 *   <li>a maker submits a range ({@value #SUBMIT}) and a different checker approves or rejects it ({@value #APPROVE});
 *       the maker may withdraw their own pending range; both need a grant that reaches the region;</li>
 *   <li>ranges with the same prefix never overlap, whatever their region;</li>
 *   <li>licence issue takes the next number of the region from its oldest active range with numbers left; every
 *       number handed out is kept with what it went to;</li>
 *   <li>the report shows total, used and available numbers per range and per region ({@value #READ}).</li>
 * </ul>
 */
public class LicenceNumberService {

    public static final String SUBMIT = "licensing.number-range.submit";
    public static final String APPROVE = "licensing.number-range.approve";
    public static final String READ = "licensing.number-range.read";
    static final String ENTITY = "LicenceNumberRange";
    static final int MAX_PAGE = 500;

    private final LicenceNumberRepository ranges;
    private final AuditRecorder audit;
    private final Clock clock;

    public LicenceNumberService(LicenceNumberRepository ranges, AuditRecorder audit, Clock clock) {
        this.ranges = ranges;
        this.audit = audit;
        this.clock = clock;
    }

    /** Totals of one range, or of a region when {@code range} is null. */
    public record Usage(String region, LicenceNumberRange range, long total, long used, long available) {
    }

    /** The report of a region: one line per range, then the region's totals. */
    public record Report(String region, List<Usage> ranges, Usage totals) {
    }

    @Transactional
    public LicenceNumberRange submit(LicenceNumberRange.Spec spec, String reason, Caller caller) {
        Channel channel = LicenceClassService.channelOf(caller);
        requireIn(caller, SUBMIT, spec.region());
        ranges.lockPrefix(spec.prefix());
        requireNoOverlap(spec, null);
        LicenceNumberRange range = LicenceNumberRange.submit(UUID.randomUUID(), spec, reason, caller.actor(), channel,
                clock.instant());
        ranges.insert(range);
        audit.record("LICENCE_NUMBER_RANGE_SUBMITTED", ENTITY, range.id().toString(), null, snapshot(range),
                range.submitted().reason());
        return range;
    }

    @Transactional
    public LicenceNumberRange approve(UUID id, String reason, Caller caller) {
        Channel channel = LicenceClassService.channelOf(caller);
        LicenceNumberRange found = ranges.find(id).orElseThrow(() -> notFound(id));
        requireIn(caller, APPROVE, found.spec().region());
        ranges.lockPrefix(found.spec().prefix());
        LicenceNumberRange range = ranges.findForUpdate(id).orElseThrow(() -> notFound(id));
        range.approve(caller.actor(), channel, reason, clock.instant());
        requireNoOverlap(range.spec(), range.id());
        ranges.saveDecision(range);
        audit.record("LICENCE_NUMBER_RANGE_APPROVED", ENTITY, id.toString(), null, snapshot(range), reason);
        return range;
    }

    @Transactional
    public LicenceNumberRange reject(UUID id, String reason, Caller caller) {
        Channel channel = LicenceClassService.channelOf(caller);
        LicenceNumberRange found = ranges.find(id).orElseThrow(() -> notFound(id));
        boolean withdrawal = found.submitted().actor().id().equals(caller.actor().id());
        requireIn(caller, withdrawal ? SUBMIT : APPROVE, found.spec().region());
        LicenceNumberRange range = ranges.findForUpdate(id).orElseThrow(() -> notFound(id));
        range.reject(caller.actor(), channel, reason, clock.instant());
        ranges.saveDecision(range);
        audit.record(withdrawal ? "LICENCE_NUMBER_RANGE_WITHDRAWN" : "LICENCE_NUMBER_RANGE_REJECTED", ENTITY,
                id.toString(), null, snapshot(range), range.decided().reason());
        return range;
    }

    @Transactional(readOnly = true)
    public LicenceNumberRange get(UUID id, Caller caller) {
        LicenceNumberRange range = ranges.find(id).orElseThrow(() -> notFound(id));
        requireIn(caller, READ, range.spec().region());
        return range;
    }

    /** Ranges of a region; without a region, those of every region the caller's grants reach. */
    @Transactional(readOnly = true)
    public List<LicenceNumberRange> list(String region, Caller caller) {
        if (region != null) {
            requireIn(caller, READ, region);
        } else {
            LicenceClassService.require(caller, READ);
        }
        return ranges.byRegion(region).stream()
                .filter(r -> caller.grants().allowsInRegion(READ, r.spec().region())).toList();
    }

    /** Available and used numbers of a region (DL-061). */
    @Transactional(readOnly = true)
    public Report report(String region, Caller caller) {
        requireIn(caller, READ, region);
        List<Usage> lines = ranges.byRegion(region).stream().filter(r -> r.status() != LicenceNumberRange.Status.REJECTED)
                .map(r -> new Usage(region, r, r.total(), r.used(), r.available())).toList();
        return new Report(region, lines, new Usage(region, null,
                lines.stream().filter(u -> u.range().status() == LicenceNumberRange.Status.ACTIVE)
                        .mapToLong(Usage::total).sum(),
                lines.stream().mapToLong(Usage::used).sum(), lines.stream().mapToLong(Usage::available).sum()));
    }

    /** The numbers handed out from a range, in order. */
    @Transactional(readOnly = true)
    public List<UsedNumber> used(UUID rangeId, int offset, int limit, Caller caller) {
        get(rangeId, caller);
        if (offset < 0 || limit < 1 || limit > MAX_PAGE) {
            throw DomainException.validation("PAGE_INVALID", "offset must be 0 or more and limit 1 to " + MAX_PAGE,
                    List.of("DL-061"), MAX_PAGE);
        }
        return ranges.usedInRange(rangeId, offset, limit);
    }

    /**
     * Takes the next licence number of a region for {@code allocatedTo} (a licence or permit id). Runs inside the
     * caller's transaction, which checked the permissions of the command it serves.
     */
    @Transactional
    public UsedNumber allocate(String region, String allocatedTo) {
        return allocate(region, allocatedTo, null);
    }

    /** {@link #allocate(String, String)} audited as {@code as} (a system actor, for work driven by events). */
    @Transactional
    public UsedNumber allocate(String region, String allocatedTo, CurrentActor as) {
        ranges.lockRegion(region);
        for (LicenceNumberRange candidate : ranges.activeInRegion(region)) {
            if (!candidate.hasNumbersLeft()) {
                continue;
            }
            LicenceNumberRange range = ranges.findForUpdate(candidate.id()).orElseThrow();
            if (!range.hasNumbersLeft()) {
                continue;
            }
            long number = range.take();
            ranges.saveNext(range);
            Instant now = clock.instant();
            UsedNumber used = new UsedNumber(range.spec().display(number), range.id(), region, number, now,
                    allocatedTo);
            ranges.insertUsed(used);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("licenceNumber", used.licenceNumber());
            after.put("region", region);
            after.put("rangeId", range.id().toString());
            after.put("allocatedTo", allocatedTo);
            if (as == null) {
                audit.record("LICENCE_NUMBER_ALLOCATED", ENTITY, range.id().toString(), null, after, null);
            } else {
                audit.recordAs(as, "LICENCE_NUMBER_ALLOCATED", ENTITY, range.id().toString(), null, after, null);
            }
            return used;
        }
        throw DomainException.conflict("LICENCE_NUMBERS_EXHAUSTED",
                "region " + region + " has no active licence number range with numbers left", List.of("DL-061"),
                region);
    }

    private void requireNoOverlap(LicenceNumberRange.Spec spec, UUID self) {
        for (LicenceNumberRange other : ranges.openWithPrefix(spec.prefix())) {
            if (!other.id().equals(self) && other.spec().overlaps(spec)) {
                throw DomainException.conflict("NUMBER_RANGE_OVERLAP",
                        "the numbers overlap range " + other.id() + " (" + other.spec().region() + ")",
                        List.of("DL-061"), other.id().toString(), other.spec().region());
            }
        }
    }

    private static void requireIn(Caller caller, String permission, String region) {
        if (!caller.grants().allowsInRegion(permission, region)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with licence numbers of region " + region, List.of("CC-001"));
        }
    }

    private static DomainException notFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "NUMBER_RANGE_NOT_FOUND",
                "licence number range " + id + " does not exist", List.of("DL-061"));
    }

    static Map<String, Object> snapshot(LicenceNumberRange r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("region", r.spec().region());
        m.put("prefix", r.spec().prefix());
        m.put("width", r.spec().width());
        m.put("firstNumber", r.spec().firstNumber());
        m.put("lastNumber", r.spec().lastNumber());
        m.put("nextNumber", r.nextNumber());
        m.put("status", r.status().name());
        return m;
    }
}

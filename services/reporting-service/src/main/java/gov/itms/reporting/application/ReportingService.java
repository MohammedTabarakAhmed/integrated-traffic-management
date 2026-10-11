package gov.itms.reporting.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.reporting.domain.DomainException;
import gov.itms.reporting.domain.Report;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports over the warehouse (RPT-001, RPT-004, RPT-020, RPT-022).
 * <ul>
 *   <li>Intake: every domain event of the consumed topics is kept once as a fact with its scalar fields; personal
 *       identifiers are pseudonymised first, so the warehouse holds no readable identity.</li>
 *   <li>Reports: configured definitions count, and optionally sum an amount of, event types per day or month,
 *       region and type, for a date range with region, channel and status filters. A caller sees only regions their
 *       {@value #READ} grants reach (403 when they ask for another); facts without a region need a national grant.
 *       Every run is audited.</li>
 * </ul>
 */
public class ReportingService {

    private static final Logger LOG = LoggerFactory.getLogger(ReportingService.class);
    public static final String READ = "report.read";

    /** Settings (configuration, NFR-042): the definitions and the longest range answered at once (RPT-005). */
    public record Settings(List<Report.Definition> definitions, int maxDays) {
        public Settings {
            definitions = List.copyOf(definitions);
        }
    }

    private final Ports.Warehouse warehouse;
    private final Pseudonymiser pseudonyms;
    private final AuditRecorder audit;
    private final Settings settings;
    private final Clock clock;

    public ReportingService(Ports.Warehouse warehouse, Pseudonymiser pseudonyms, AuditRecorder audit,
                            Settings settings, Clock clock) {
        this.warehouse = warehouse;
        this.pseudonyms = pseudonyms;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    /** Keeps one domain event as a fact (RPT-020); a redelivery changes nothing. */
    public boolean ingest(String eventId, String type, String subject, Instant time, Map<String, Object> data) {
        if (eventId == null || type == null) {
            return false;
        }
        Map<String, String> kept = new LinkedHashMap<>();
        if (data != null) {
            data.forEach((k, v) -> {
                if (v != null && !(v instanceof Map) && !(v instanceof List)) {
                    String s = String.valueOf(v);
                    kept.put(k, pseudonyms.personal(k) && !s.isEmpty() ? pseudonyms.pseudonym(s) : s);
                }
            });
        }
        String region = Optional.ofNullable(kept.get("region")).orElse(kept.get("regionOpened"));
        boolean added = warehouse.insert(new Report.Fact(eventId, type,
                subject == null || pseudonyms.personal("subject") ? null : subject, region, kept.get("channel"),
                time == null ? clock.instant() : time, kept));
        if (!added) {
            LOG.debug("Event {} already in the warehouse", eventId);
        }
        return added;
    }

    public List<Report.Definition> definitions() {
        return settings.definitions();
    }

    public Report.Result run(Report.Request request, Ports.Caller caller) {
        Report.Definition d = settings.definitions().stream().filter(x -> x.code().equals(request.code()))
                .findFirst().orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                        "REPORT_NOT_FOUND", "no report " + request.code(), List.of("RPT-001")));
        long days = ChronoUnit.DAYS.between(request.from(), request.to()) + 1;
        if (days > settings.maxDays()) {
            throw new DomainException(DomainException.Kind.VALIDATION, "PERIOD_TOO_LONG",
                    "a report covers at most " + settings.maxDays() + " days at once", List.of("RPT-005"));
        }
        for (String r : request.regions()) {
            if (!caller.grants().allowsInRegion(r)) {
                throw new DomainException(DomainException.Kind.FORBIDDEN, "REGION_OUT_OF_SCOPE",
                        "your data scope does not reach region " + r, List.of("RPT-004", "CC-001"));
            }
        }
        Instant from = start(request.from());
        Instant to = start(request.to().plusDays(1));
        List<Report.Row> rows = warehouse.aggregate(d.eventTypes(), d.amountField(), from, to, request.period(),
                request.channel(), request.status()).stream()
                .filter(r -> request.regions().isEmpty()
                        || (r.region() != null && request.regions().contains(r.region())))
                .filter(r -> r.region() == null ? caller.grants().national()
                        : caller.grants().allowsInRegion(r.region()))
                .toList();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("report", d.code());
        run.put("from", request.from().toString());
        run.put("to", request.to().toString());
        run.put("regions", request.regions().stream().sorted().toList());
        run.put("rows", rows.size());
        audit.record("REPORT_RUN", "Report", d.code(), null, run, null);
        return new Report.Result(d, request, rows, clock.instant());
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}

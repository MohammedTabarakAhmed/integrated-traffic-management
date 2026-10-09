package gov.itms.violation.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.violation.domain.BlackPoints;
import gov.itms.violation.domain.CatalogueEntry;
import gov.itms.violation.domain.DomainException;
import gov.itms.violation.domain.Violation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Violations, fines and black points — the Increment 1 core (ADR-056).
 * <ul>
 *   <li>catalogue versions with effective dates, regional variants, speed bands and repeat rules
 *       ({@value #CATALOGUE_MANAGE}; VF-001..VF-004);</li>
 *   <li>officers and devices record violations ({@value #RECORD}); the version in force on the violation date and
 *       region applies; the responsible party is the identified driver, otherwise the owner (VF-014); the fine is
 *       registered as a payable at payment-service and the responsible party is notified by event (VF-015,
 *       VF-070);</li>
 *   <li>black points go to the responsible file and expire after the configured months (VF-030, VF-031); crossing a
 *       warning threshold notifies the driver (VF-033); reaching the suspension threshold suspends the licence for
 *       the configured period of that ordinal and places a hold on licence services (VF-032, UAT-1);</li>
 *   <li>payment-service's confirmation marks violations PAID (UAT-4); a supervisor cancels an open violation with a
 *       reason code from the configured list ({@value #CANCEL}; VF-021); every status change is audited (VF-020).</li>
 * </ul>
 */
public class ViolationService {

    public static final String CATALOGUE_MANAGE = "violation.catalogue.manage";
    public static final String RECORD = "violation.record";
    public static final String READ = "violation.read";
    public static final String CANCEL = "violation.cancel";
    static final String ENTITY = "Violation";

    /** Settings (configuration). */
    public record Settings(BlackPoints.Policy points, Set<String> cancelReasons, String currency, ZoneId zone,
                           Duration maxReportDelay) {
        public Settings {
            cancelReasons = Set.copyOf(cancelReasons);
        }
    }

    /** A file's black point standing (VF-030..VF-032; UAT-1). */
    public record Standing(String fileNumber, int activePoints, int threshold, BlackPoints.Suspension activeSuspension,
                           List<BlackPoints.Entry> entries, List<BlackPoints.Suspension> suspensions) {
        public Standing {
            entries = List.copyOf(entries);
            suspensions = List.copyOf(suspensions);
        }
    }

    private final Ports.ViolationRepository repo;
    private final Ports.Payables payables;
    private final Ports.Holds holds;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Settings settings;
    private final Clock clock;

    public ViolationService(Ports.ViolationRepository repo, Ports.Payables payables, Ports.Holds holds,
                            AuditRecorder audit, DomainEventRecorder events, Settings settings, Clock clock) {
        this.repo = repo;
        this.payables = payables;
        this.holds = holds;
        this.audit = audit;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    // ---- catalogue (VF-001..VF-004)

    @Transactional
    public CatalogueEntry publish(CatalogueEntry draft, String reason, Caller caller) {
        channelOf(caller);
        require(caller.grants().holds(CATALOGUE_MANAGE));
        if (reason == null || reason.strip().length() < 3 || reason.length() > 500) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("VF-002"));
        }
        LocalDate today = today();
        if (draft.effectiveFrom().isBefore(today)) {
            throw DomainException.validation("CATALOGUE_START_IN_PAST", "a catalogue change cannot start in the past",
                    List.of("VF-002"));
        }
        repo.lockCatalogue(draft.code());
        CatalogueEntry e = new CatalogueEntry(draft.code(), repo.nextCatalogueVersion(draft.code()), draft.region(),
                draft.legalArticle(), draft.descriptionNl(), draft.descriptionEn(), draft.fineMinor(),
                draft.blackPoints(), draft.impoundDays(), draft.suspensionDays(), draft.courtReferral(),
                draft.appliesTo(), draft.speedBands(), draft.repeatWithinDays(), draft.repeatFineMinor(),
                draft.financialItem(), draft.beneficiary(), draft.effectiveFrom(), draft.effectiveTo(),
                caller.actor().id(), clock.instant());
        repo.insertCatalogue(e);
        audit.record("VIOLATION_CATALOGUE_PUBLISHED", "ViolationCatalogue", e.code() + ":" + e.versionNo(), null,
                Map.of("fine", e.fineMinor(), "points", e.blackPoints(), "from", e.effectiveFrom().toString()),
                reason.strip());
        return e;
    }

    @Transactional(readOnly = true)
    public List<CatalogueEntry> catalogue() {
        return repo.latestCatalogue();
    }

    @Transactional(readOnly = true)
    public List<CatalogueEntry> versions(String code) {
        return repo.catalogueVersions(code);
    }

    // ---- recording (VF-014, VF-015, VF-030..VF-033, VF-070)

    @Transactional
    public Violation record(Violation.Report report, Caller caller) {
        channelOf(caller);
        require(caller.grants().allowsInRegion(RECORD, report.region()));
        Instant now = clock.instant();
        if (report.occurredAt().isAfter(now.plusSeconds(60))
                || report.occurredAt().isBefore(now.minus(settings.maxReportDelay()))) {
            throw DomainException.validation("OCCURRED_AT_INVALID", "the violation time is in the future or too old",
                    List.of("VF-010"));
        }
        LocalDate day = LocalDate.ofInstant(report.occurredAt(), settings.zone());
        CatalogueEntry entry = inForce(report.catalogueCode(), report.region(), day);
        String responsible = report.responsibleFileNumber();
        boolean repeat = entry.repeatWithinDays() != null && repo.hadViolationSince(responsible, entry.code(),
                report.occurredAt().minus(Duration.ofDays(entry.repeatWithinDays())));
        CatalogueEntry.Charge charge = entry.charge(report.overKmh(), repeat);
        long seq = repo.nextViolationSequence();
        String number = "V-" + day.getYear() + "-" + String.format("%09d", seq);
        Violation v = new Violation(UUID.randomUUID(), number, report, entry.code(), entry.versionNo(),
                charge.fineMinor(), charge.blackPoints(), charge.repeat(), caller.actor().id(), now,
                Violation.Status.OPEN, null, null, null);
        repo.insertViolation(v);
        if (v.fineMinor() > 0) {
            v.payableRegistered(payables.register(number, responsible, report.region(), settings.currency(),
                    v.fineMinor(), entry.financialItem(), entry.beneficiary(), entry.descriptionEn()));
            repo.updateViolation(v);
        }
        Map<String, Object> snapshot = snapshot(v, entry);
        audit.record("VIOLATION_RECORDED", ENTITY, number, null, snapshot, null);
        events.record("itms.violation.recorded", number, snapshot);
        if (v.blackPoints() > 0) {
            addPoints(v, responsible, day, CurrentActor.system("violation-black-points"));
        }
        return v;
    }

    /** Adds the points, sends warnings, and suspends when the threshold is reached (VF-030..VF-033, UAT-1). */
    private void addPoints(Violation v, String file, LocalDate occurredOn, CurrentActor actor) {
        repo.lockFile(file);
        LocalDate today = today();
        List<BlackPoints.Entry> entries = repo.points(file);
        int before = BlackPoints.active(entries, today);
        BlackPoints.Entry entry = new BlackPoints.Entry(UUID.randomUUID(), file, v.id(), v.blackPoints(), occurredOn,
                occurredOn.plusMonths(settings.points().expiryMonths()), BlackPoints.EntryStatus.ACTIVE);
        repo.insertPoints(entry);
        List<BlackPoints.Entry> all = new ArrayList<>(entries);
        all.add(entry);
        int after = BlackPoints.active(all, today);
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("fileNumber", file);
        base.put("recipientId", file);
        base.put("violationNumber", v.number());
        base.put("points", v.blackPoints());
        base.put("activePoints", after);
        audit.record("BLACK_POINTS_ADDED", "BlackPointAccount", file, Map.of("activePoints", before), base, null);
        events.record("itms.violation.points-added", file, base);
        for (Integer threshold : BlackPoints.warningsCrossed(settings.points(), before, after)) {
            Map<String, Object> warning = new LinkedHashMap<>(base);
            warning.put("warningThreshold", threshold);
            warning.put("suspensionThreshold", settings.points().suspensionThreshold());
            events.record("itms.violation.points-warning", file, warning);
        }
        List<BlackPoints.Suspension> previous = repo.suspensions(file);
        boolean suspendedNow = previous.stream().anyMatch(s -> s.activeOn(today));
        if (after >= settings.points().suspensionThreshold() && !suspendedNow) {
            suspend(file, after, previous.size() + 1, all, v, today, actor);
        }
    }

    private void suspend(String file, int points, int ordinal, List<BlackPoints.Entry> entries, Violation trigger,
                         LocalDate today, CurrentActor actor) {
        int months = settings.points().monthsOf(ordinal);
        LocalDate until = today.plusMonths(months).minusDays(1);
        UUID id = UUID.randomUUID();
        String holdId = holds.placeSuspensionHold(file, today, until, "SUSPENSION:" + id,
                "Licence suspended for " + months + " months: " + points + " active black points (VF-032)");
        BlackPoints.Suspension s = new BlackPoints.Suspension(id, file, ordinal, today, until, points, holdId,
                trigger.id());
        repo.insertSuspension(s);
        repo.setPointsStatus(entries.stream().filter(e -> e.activeOn(today)).map(BlackPoints.Entry::id).toList(),
                BlackPoints.EntryStatus.CONSUMED);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fileNumber", file);
        m.put("recipientId", file);
        m.put("suspensionId", id.toString());
        m.put("ordinal", ordinal);
        m.put("from", today.toString());
        m.put("until", until.toString());
        m.put("months", months);
        m.put("activePoints", points);
        m.put("holdId", holdId);
        m.put("violationNumber", trigger.number());
        audit.record("LICENCE_SUSPENDED", "BlackPointAccount", file, null, m, "VF-032 threshold reached");
        events.record("itms.violation.licence-suspended", file, m);
    }

    // ---- reading

    @Transactional
    public Violation get(String number, Caller caller) {
        Violation v = repo.findByNumber(number).orElseThrow(() -> notFound(number));
        require(caller.grants().allowsInRegion(READ, v.report().region()));
        audit.record("VIOLATION_VIEWED", ENTITY, number, null, null, null);
        return v;
    }

    @Transactional
    public List<Violation> byFile(String fileNumber, Violation.Status status, int limit, Caller caller) {
        require(caller.grants().holds(READ));
        if (limit < 1 || limit > 500) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1..500", List.of("VF-016"), "limit");
        }
        audit.record("VIOLATIONS_LISTED", "TrafficFile", fileNumber, null, null, null);
        return repo.byFile(fileNumber, status, limit);
    }

    @Transactional
    public Standing standing(String fileNumber, Caller caller) {
        require(caller.grants().holds(READ));
        LocalDate today = today();
        List<BlackPoints.Entry> entries = repo.points(fileNumber);
        List<BlackPoints.Suspension> suspensions = repo.suspensions(fileNumber);
        audit.record("BLACK_POINTS_VIEWED", "BlackPointAccount", fileNumber, null, null, null);
        return new Standing(fileNumber, BlackPoints.active(entries, today), settings.points().suspensionThreshold(),
                suspensions.stream().filter(s -> s.activeOn(today)).findFirst().orElse(null), entries, suspensions);
    }

    // ---- cancellation (VF-021)

    @Transactional
    public Violation cancel(String number, String reasonCode, String note, Caller caller) {
        channelOf(caller);
        Violation v = repo.findForUpdate(number).orElseThrow(() -> notFound(number));
        require(caller.grants().allowsInRegion(CANCEL, v.report().region()));
        if (reasonCode == null || !settings.cancelReasons().contains(reasonCode)) {
            throw DomainException.validation("CANCEL_REASON_INVALID", "choose a cancellation reason from the list",
                    List.of("VF-021"), String.join(", ", settings.cancelReasons().stream().sorted().toList()));
        }
        v.cancel(reasonCode, clock.instant());
        if (v.payableId() != null) {
            payables.cancel(v.payableId(), "violation cancelled: " + reasonCode);
        }
        repo.updateViolation(v);
        repo.cancelPointsOf(v.id());
        audit.record("VIOLATION_CANCELLED", ENTITY, number, Map.of("status", "OPEN"),
                Map.of("status", "CANCELLED", "reasonCode", reasonCode), note);
        events.record("itms.violation.cancelled", number, Map.of("violationNumber", number, "reasonCode", reasonCode,
                "recipientId", v.report().responsibleFileNumber()));
        return v;
    }

    // ---- payment (VF-070, UAT-4)

    /** From {@code itms.payment.confirmed}: marks the violation PAID; repeats and unknown numbers are ignored. */
    @Transactional
    public boolean onPaid(String number) {
        Violation v = repo.findForUpdate(number).orElse(null);
        if (v == null || v.status() != Violation.Status.OPEN) {
            return false;
        }
        v.paid(clock.instant());
        repo.updateViolation(v);
        CurrentActor system = CurrentActor.system("violation-payment-consumer");
        audit.recordAs(system, "VIOLATION_PAID", ENTITY, number, Map.of("status", "OPEN"), Map.of("status", "PAID"),
                null);
        events.recordAs(system, "itms.violation.paid", number, Map.of("violationNumber", number, "recipientId",
                v.report().responsibleFileNumber()));
        return true;
    }

    /** From {@code itms.payment.receipt-cancelled}: the violation is open again. */
    @Transactional
    public boolean onReceiptCancelled(String number) {
        Violation v = repo.findForUpdate(number).orElse(null);
        if (v == null || v.status() != Violation.Status.PAID) {
            return false;
        }
        v.reopened();
        repo.updateViolation(v);
        audit.recordAs(CurrentActor.system("violation-payment-consumer"), "VIOLATION_REOPENED", ENTITY, number,
                Map.of("status", "PAID"), Map.of("status", "OPEN"), "receipt cancelled");
        return true;
    }

    // ---- helpers

    private CatalogueEntry inForce(String code, String region, LocalDate day) {
        List<CatalogueEntry> versions = repo.catalogueVersions(code);
        return versions.stream().filter(e -> region.equals(e.region()) && e.inForce(day)).findFirst()
                .or(() -> versions.stream().filter(e -> e.region() == null && e.inForce(day)).findFirst())
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "CATALOGUE_NOT_IN_FORCE",
                        "violation " + code + " is not in the catalogue on " + day, List.of("VF-002"), code,
                        day.toString()));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), settings.zone());
    }

    static Map<String, Object> snapshot(Violation v, CatalogueEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("violationNumber", v.number());
        m.put("catalogueCode", v.catalogueCode());
        m.put("catalogueVersion", v.catalogueVersion());
        m.put("legalArticle", e == null ? null : e.legalArticle());
        m.put("occurredAt", v.report().occurredAt().toString());
        m.put("region", v.report().region());
        m.put("plate", v.report().plate());
        m.put("responsibleFileNumber", v.report().responsibleFileNumber());
        m.put("recipientId", v.report().responsibleFileNumber());
        m.put("fine", v.fineMinor());
        m.put("points", v.blackPoints());
        m.put("repeat", v.repeat());
        m.put("status", v.status().name());
        m.put("payableId", Objects.toString(v.payableId(), null));
        return m;
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with violations", List.of("CC-001"));
        }
    }

    static void channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw DomainException.validation("CHANNEL_REQUIRED", "the request does not name its channel (X-Channel)",
                    List.of("CC-120"));
        }
    }

    private static DomainException notFound(String number) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "VIOLATION_NOT_FOUND",
                "violation " + number + " not found", List.of("VF-016"));
    }
}

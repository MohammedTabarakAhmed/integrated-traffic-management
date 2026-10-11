package gov.itms.violation.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.violation.domain.BlackPoints;
import gov.itms.violation.domain.CatalogueEntry;
import gov.itms.violation.domain.DomainException;
import gov.itms.violation.domain.UnpaidHold;
import gov.itms.violation.domain.Violation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *       reason code from the configured list ({@value #CANCEL}; VF-021); every status change is audited (VF-020);</li>
 *   <li>a violation still open after the configured period places an UNPAID_FINES hold on the vehicle or the
 *       driver's file, lifted once it is paid or cancelled (VF-023; ADR-058).</li>
 * </ul>
 */
public class ViolationService {

    private static final Logger LOG = LoggerFactory.getLogger(ViolationService.class);

    public static final String CATALOGUE_MANAGE = "violation.catalogue.manage";
    public static final String RECORD = "violation.record";
    public static final String READ = "violation.read";
    public static final String CANCEL = "violation.cancel";
    static final String ENTITY = "Violation";

    /** Settings (configuration). */
    public record Settings(BlackPoints.Policy points, Set<String> cancelReasons, String currency, ZoneId zone,
                           Duration maxReportDelay, UnpaidHold.Policy unpaid) {
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
    private final Ports.UnpaidFineHolds unpaidHolds;
    private final Ports.Rentals rentals;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Settings settings;
    private final Clock clock;

    /** Without rental contract lookup: violations go to the identified driver or the owner. */
    public ViolationService(Ports.ViolationRepository repo, Ports.Payables payables, Ports.Holds holds,
                            Ports.UnpaidFineHolds unpaidHolds, AuditRecorder audit, DomainEventRecorder events,
                            Settings settings, Clock clock) {
        this(repo, payables, holds, unpaidHolds, (plate, at) -> java.util.Optional.empty(), audit, events, settings,
                clock);
    }

    public ViolationService(Ports.ViolationRepository repo, Ports.Payables payables, Ports.Holds holds,
                            Ports.UnpaidFineHolds unpaidHolds, Ports.Rentals rentals, AuditRecorder audit,
                            DomainEventRecorder events, Settings settings, Clock clock) {
        this.repo = repo;
        this.payables = payables;
        this.holds = holds;
        this.unpaidHolds = unpaidHolds;
        this.rentals = rentals;
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
        if (report.source() == Violation.Source.PARTNER) {
            throw DomainException.validation("SOURCE_INVALID", "partner-state violations arrive only by exchange",
                    List.of("XB-002"));
        }
        Instant now = clock.instant();
        if (report.occurredAt().isAfter(now.plusSeconds(60))
                || report.occurredAt().isBefore(now.minus(settings.maxReportDelay()))) {
            throw DomainException.validation("OCCURRED_AT_INVALID", "the violation time is in the future or too old",
                    List.of("VF-010"));
        }
        LocalDate day = LocalDate.ofInstant(report.occurredAt(), settings.zone());
        CatalogueEntry entry = inForce(report.catalogueCode(), report.region(), day);
        // VF-014, TR-032: with no identified driver, a rental contract covering the time makes the renter responsible.
        Ports.Rentals.Cover cover = report.driverFileNumber() == null && report.plate() != null
                ? rentals.covering(report.plate(), report.occurredAt()).orElse(null) : null;
        if (cover != null) {
            report = report.coveredBy(cover.contractId(), cover.renterFile());
        }
        String responsible = report.responsibleFileNumber();
        boolean repeat = entry.repeatWithinDays() != null && repo.hadViolationSince(responsible, entry.code(),
                report.occurredAt().minus(Duration.ofDays(entry.repeatWithinDays())));
        CatalogueEntry.Charge charge = entry.charge(report.overKmh(), repeat);
        String number = nextNumber(day);
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
        if (cover != null) {
            rentalTransfer(v, cover);
        }
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

    /** Loads a violation for an objection using the objection grant's region scope (VF-050). */
    @Transactional(readOnly = true)
    public Violation getForObjection(String number, Caller caller) {
        Violation v = repo.findByNumber(number).orElseThrow(() -> notFound(number));
        require(caller.grants().allowsInRegion(ObjectionService.SUBMIT, v.report().region()));
        audit.record("VIOLATION_OBJECTION_VIEWED", ENTITY, number, null, null, null);
        return v;
    }

    @Transactional
    public void pauseForObjection(String number, Caller caller) {
        Violation v = repo.findForUpdate(number).orElseThrow(() -> notFound(number));
        require(caller.grants().allowsInRegion(ObjectionService.SUBMIT, v.report().region()));
        if (v.payableId() != null) {
            payables.hold(v.payableId(), "OBJECTION:" + number, "objection is awaiting review");
        }
    }

    /** For an objection forwarded by a partner state (XB-005): no caller grants; locks the violation. */
    Optional<Violation> lockForPartnerObjection(String number) {
        Violation v = repo.findForUpdate(number).orElse(null);
        if (v != null && v.status() == Violation.Status.OPEN && v.payableId() != null) {
            payables.hold(v.payableId(), "OBJECTION:" + number, "objection forwarded by a partner state");
        }
        return Optional.ofNullable(v);
    }

    @Transactional
    public void resumeAfterObjection(String number, String reason, Caller caller) {
        Violation v = repo.findForUpdate(number).orElseThrow(() -> notFound(number));
        require(caller.grants().allowsInRegion(ObjectionService.REVIEW, v.report().region()));
        if (v.payableId() != null) {
            payables.liftHold(v.payableId(), "OBJECTION:" + number, reason);
        }
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
        return cancelWithPermission(number, reasonCode, note, caller, CANCEL);
    }

    /** Accepted objections cancel through the reviewer grant without requiring a second cancellation grant. */
    @Transactional
    public Violation cancelFromObjection(String number, String reasonCode, String note, Caller caller) {
        return cancelWithPermission(number, reasonCode, note, caller, ObjectionService.REVIEW);
    }

    private Violation cancelWithPermission(String number, String reasonCode, String note, Caller caller,
                                           String permission) {
        channelOf(caller);
        Violation v = repo.findForUpdate(number).orElseThrow(() -> notFound(number));
        require(caller.grants().allowsInRegion(permission, v.report().region()));
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
        tryLiftUnpaidHold(v, CurrentActor.system("violation-unpaid-holds"));
        return v;
    }

    /**
     * TR-032, UAT-2: the violation went to the renter's file; the rental company is notified. A visitor renter without
     * a traffic file leaves the owner (the company) responsible, with the contract kept for staff (Q-081).
     */
    private void rentalTransfer(Violation v, Ports.Rentals.Cover cover) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("violationNumber", v.number());
        detail.put("plate", v.report().plate());
        detail.put("occurredAt", v.report().occurredAt().toString());
        detail.put("rentalContractId", cover.contractId().toString());
        detail.put("companyFile", cover.companyFile());
        detail.put("recipientId", cover.companyFile());
        detail.put("transferred", cover.renterFile() != null);
        if (cover.renterFile() != null) {
            detail.put("renterFileNumber", cover.renterFile());
        }
        audit.record(cover.renterFile() != null ? "VIOLATION_TRANSFERRED_TO_RENTER" : "VIOLATION_RENTER_IS_VISITOR",
                ENTITY, v.number(), Map.of("responsibleFileNumber", v.report().ownerFileNumber()),
                Map.of("responsibleFileNumber", v.report().responsibleFileNumber()), "TR-032");
        events.record(cover.renterFile() != null ? "itms.violation.transferred-to-renter"
                : "itms.violation.renter-is-visitor", v.number(), detail);
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
        tryLiftUnpaidHold(v, system);
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

    // ---- unpaid violations (VF-023)

    /** Open violations overdue for an unpaid hold, oldest first; none when the policy is switched off (VF-023). */
    @Transactional(readOnly = true)
    public List<String> overdueForHold() {
        UnpaidHold.Policy policy = settings.unpaid();
        if (!policy.enabled()) {
            return List.of();
        }
        return repo.overdueWithoutHold(clock.instant().minus(policy.after()), policy.batchSize()).stream()
                .map(Violation::number).toList();
    }

    /**
     * Places an UNPAID_FINES hold for the violation if it is still open, overdue, not held and not under objection
     * (VF-023, Q-012; VF-052 and Q-074 (f): payment is paused while an objection is pending or referred to court, so
     * the hold waits until it is decided). The violation row stays locked while the hold is placed, so a second
     * instance, a payment or a new objection waits for it.
     */
    @Transactional
    public boolean holdIfOverdue(String number) {
        UnpaidHold.Policy policy = settings.unpaid();
        Violation v = repo.findForUpdate(number).orElse(null);
        Instant now = clock.instant();
        if (!policy.enabled() || v == null || v.status() != Violation.Status.OPEN
                || v.recordedAt().isAfter(now.minus(policy.after())) || repo.activeUnpaidHold(v.id()).isPresent()
                || repo.underObjection(v.id())) {
            return false;
        }
        UnpaidHold.Subject subject = UnpaidHold.subjectOf(v);
        UUID id = UUID.randomUUID();
        String holdId = unpaidHolds.place(subject, "UNPAID-" + id,
                "Violation " + number + " unpaid for more than " + period(policy.after()) + " (VF-023)");
        UnpaidHold hold = new UnpaidHold(id, v.id(), number, subject, holdId, now, null);
        repo.insertUnpaidHold(hold);
        CurrentActor system = CurrentActor.system("violation-unpaid-holds");
        Map<String, Object> detail = unpaidSnapshot(hold, v);
        audit.recordAs(system, "UNPAID_HOLD_PLACED", ENTITY, number, null, detail, "VF-023");
        events.recordAs(system, "itms.violation.unpaid-hold-placed", number, detail);
        return true;
    }

    /** Violations paid or cancelled whose unpaid hold is still active, oldest first (VF-023). */
    @Transactional(readOnly = true)
    public List<String> settledWithHold() {
        return repo.settledWithActiveHold(settings.unpaid().batchSize()).stream()
                .map(UnpaidHold::violationNumber).toList();
    }

    /** Lifts the unpaid hold of a paid or cancelled violation (VF-023); hold-eligibility-service errors propagate. */
    @Transactional
    public boolean liftIfSettled(String number) {
        Violation v = repo.findForUpdate(number).orElse(null);
        if (v == null || v.status() == Violation.Status.OPEN) {
            return false;
        }
        return liftUnpaidHold(v, CurrentActor.system("violation-unpaid-holds"));
    }

    /** Lifts at once when a violation is paid or cancelled; if that fails the job lifts it on its next run. */
    private void tryLiftUnpaidHold(Violation v, CurrentActor actor) {
        try {
            liftUnpaidHold(v, actor);
        } catch (Ports.UnavailableException e) {
            LOG.warn("Unpaid hold of {} not lifted now ({}); the job retries", v.number(), e.code());
        }
    }

    private boolean liftUnpaidHold(Violation v, CurrentActor actor) {
        UnpaidHold hold = repo.activeUnpaidHold(v.id()).orElse(null);
        if (hold == null) {
            return false;
        }
        unpaidHolds.lift(hold.holdId(), "Violation " + v.number() + " "
                + v.status().name().toLowerCase(Locale.ROOT) + " (VF-023)");
        Instant now = clock.instant();
        repo.unpaidHoldLifted(hold.id(), now);
        audit.recordAs(actor, "UNPAID_HOLD_LIFTED", ENTITY, v.number(), unpaidSnapshot(hold, v),
                Map.of("liftedAt", now.toString(), "violationStatus", v.status().name()), "VF-023");
        return true;
    }

    private static Map<String, Object> unpaidSnapshot(UnpaidHold hold, Violation v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("violationNumber", v.number());
        m.put("holdId", hold.holdId());
        m.put("subjectType", hold.subject().type().name());
        m.put("subjectId", hold.subject().id());
        m.put("recipientId", v.report().responsibleFileNumber());
        return m;
    }

    private static String period(Duration d) {
        return d.toDays() >= 1 && d.toHours() % 24 == 0 ? d.toDays() + " days" : d.toMinutes() + " minutes";
    }

    // ---- helpers

    CatalogueEntry inForce(String code, String region, LocalDate day) {
        List<CatalogueEntry> versions = repo.catalogueVersions(code);
        return versions.stream().filter(e -> region.equals(e.region()) && e.inForce(day)).findFirst()
                .or(() -> versions.stream().filter(e -> e.region() == null && e.inForce(day)).findFirst())
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "CATALOGUE_NOT_IN_FORCE",
                        "violation " + code + " is not in the catalogue on " + day, List.of("VF-002"), code,
                        day.toString()));
    }

    /** The next violation number for a violation that happened on {@code day}. */
    String nextNumber(LocalDate day) {
        return "V-" + day.getYear() + "-" + String.format("%09d", repo.nextViolationSequence());
    }

    LocalDate dayOf(Instant at) {
        return LocalDate.ofInstant(at, settings.zone());
    }

    String currency() {
        return settings.currency();
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

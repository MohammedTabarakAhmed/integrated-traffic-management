package gov.itms.violation.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.violation.domain.CatalogueEntry;
import gov.itms.violation.domain.DomainException;
import gov.itms.violation.domain.InstalmentPlan;
import gov.itms.violation.domain.Violation;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Authority's own instalment plans (VF-071, VF-073; ADR-062). Staff open a plan for a file's open violations;
 * their payables are replaced by one payable per monthly instalment ({@code <plan>-<nn>}), the violations stay OPEN,
 * and their unpaid holds (VF-023) are lifted while the plan is ACTIVE. An instalment still unpaid after its due date
 * plus the grace period makes the plan LATE, so the unpaid-hold job restores the holds; paying the late instalments
 * makes it ACTIVE again. The last payment completes the plan and marks the violations PAID.
 */
public class InstalmentService {

    public static final String CREATE = "violation.instalment.create";
    /** A customer asking online for an own plan for their own violations (iam V60); CUSTOMER role only. */
    public static final String CUSTOMER_CREATE = "customer.instalment.request";
    public static final String PLAN_PREFIX = "IP-";

    private static final Logger LOG = LoggerFactory.getLogger(InstalmentService.class);
    private static final String SYSTEM = "violation-instalments";
    private static final String ENTITY = "InstalmentPlan";
    private static final int MAX_VIOLATIONS = 50;
    private static final int BATCH = 100;

    /** Plan terms, currency and business zone (configuration, {@code itms.violations.instalments.*}). */
    public record Settings(InstalmentPlan.Terms terms, String currency, ZoneId zone) {
    }

    private final Ports.InstalmentPlans plans;
    private final Ports.ViolationRepository repo;
    private final Ports.CrossborderLinks links;
    private final Ports.Payables payables;
    private final ViolationService violations;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Settings settings;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public InstalmentService(Ports.InstalmentPlans plans, Ports.ViolationRepository repo,
                             Ports.CrossborderLinks links, Ports.Payables payables, ViolationService violations,
                             AuditRecorder audit, DomainEventRecorder events, Settings settings, Clock clock) {
        this.plans = plans;
        this.repo = repo;
        this.links = links;
        this.payables = payables;
        this.violations = violations;
        this.audit = audit;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    /** VF-071: opens an own instalment plan for open violations of one traffic file. */
    @Transactional
    public InstalmentPlan create(String fileNumber, String region, List<String> violationNumbers, int months,
                                 Caller caller) {
        ViolationService.channelOf(caller);
        require(caller.grants().allowsInRegion(CREATE, region));
        return open(fileNumber, region, violationNumbers, months, caller, false);
    }

    /**
     * VF-071 online (ADR-067 d): the signed-in customer asks for an own plan for their own open violations;
     * {@code fileNumber} is their traffic file, found from their token. Another file's violation answers 404. The plan
     * is kept in the region of the earliest of its violations (interim, Q-086).
     */
    @Transactional
    public InstalmentPlan createOwn(String fileNumber, List<String> violationNumbers, int months, Caller caller) {
        ViolationService.channelOf(caller);
        require(caller.grants().holds(CUSTOMER_CREATE));
        return open(fileNumber, null, violationNumbers, months, caller, true);
    }

    private InstalmentPlan open(String fileNumber, String chosenRegion, List<String> violationNumbers, int months,
                                Caller caller, boolean own) {
        if (violationNumbers == null || violationNumbers.isEmpty() || violationNumbers.size() > MAX_VIOLATIONS
                || violationNumbers.stream().distinct().count() != violationNumbers.size()) {
            throw DomainException.validation("INSTALMENT_PLAN_INVALID",
                    "choose 1 to " + MAX_VIOLATIONS + " different violations", List.of("VF-071"));
        }
        // Locked in a fixed order, so two plans opened at once for overlapping violations cannot deadlock.
        List<Violation> locked = new ArrayList<>();
        for (String number : violationNumbers.stream().sorted().toList()) {
            locked.add(eligible(number, fileNumber, caller, own));
        }
        String region = chosenRegion != null ? chosenRegion : locked.stream()
                .min(java.util.Comparator.comparing(Violation::recordedAt).thenComparing(Violation::number))
                .map(v -> v.report().region()).orElseThrow();
        Map<Violation, Long> due = new LinkedHashMap<>();
        for (Violation v : locked) {
            due.put(v, repo.reducedFine(v.id()).orElse(v.fineMinor()));
        }
        long total = due.values().stream().mapToLong(Long::longValue).sum();
        InstalmentPlan.check(settings.terms(), total, months);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, settings.zone());
        String number = PLAN_PREFIX + today.getYear() + "-" + String.format("%09d", plans.nextPlanSequence());
        List<InstalmentPlan.Instalment> schedule = InstalmentPlan.schedule(total, months, today);
        Map<String, Long> byItem = byFinancialItem(due);

        for (Violation v : locked) {
            payables.cancel(v.payableId(), "moved to instalment plan " + number);
            v.payableRegistered(null);
            repo.updateViolation(v);
        }
        List<InstalmentPlan.Instalment> instalments = new ArrayList<>();
        long paidBefore = 0;
        for (InstalmentPlan.Instalment i : schedule) {
            List<Ports.Payables.Line> lines = lines(byItem, total, paidBefore, paidBefore + i.amountMinor(),
                    "Instalment " + i.seq() + " of " + months + ", plan " + number);
            paidBefore += i.amountMinor();
            UUID payable = payables.registerInstalment(InstalmentPlan.itemRef(number, i.seq()), fileNumber, region,
                    settings.currency(), lines);
            instalments.add(new InstalmentPlan.Instalment(i.seq(), i.dueDate(), i.amountMinor(), payable, false,
                    null));
        }
        InstalmentPlan plan = new InstalmentPlan(UUID.randomUUID(), number, fileNumber, region, settings.currency(),
                total, months, settings.terms().graceDays(), InstalmentPlan.Status.ACTIVE, caller.actor().id(), now,
                null, locked.stream().map(Violation::number).toList(), instalments);
        if (!plans.insert(plan)) {
            throw DomainException.conflict("VIOLATION_IN_INSTALMENT_PLAN", "a violation is already in a plan",
                    List.of("VF-071"));
        }
        Map<String, Object> snapshot = snapshot(plan);
        audit.record("INSTALMENT_PLAN_CREATED", ENTITY, number, null, snapshot, null);
        events.record("itms.violation.instalment-plan-created", number, snapshot);
        // VF-073: the plan covers its violations, so their unpaid holds are lifted (the job retries a failure).
        for (Violation v : locked) {
            violations.tryLiftCovered(v.number());
        }
        return plan;
    }

    @Transactional
    public InstalmentPlan get(String number, Caller caller) {
        InstalmentPlan plan = plans.find(number).orElseThrow(() -> notFound(number));
        require(caller.grants().allowsInRegion(ViolationService.READ, plan.region()));
        audit.record("INSTALMENT_PLAN_VIEWED", ENTITY, number, null, null, null);
        return plan;
    }

    /** The file's plans in regions the caller's read grant reaches (CC-001). */
    @Transactional
    public List<InstalmentPlan> byFile(String fileNumber, int limit, Caller caller) {
        require(caller.grants().holds(ViolationService.READ));
        if (limit < 1 || limit > 100) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1..100", List.of("VF-071"), "limit");
        }
        audit.record("INSTALMENT_PLANS_LISTED", "TrafficFile", fileNumber, null, null, null);
        return plans.byFile(fileNumber, limit).stream()
                .filter(p -> caller.grants().allowsInRegion(ViolationService.READ, p.region())).toList();
    }

    /** The signed-in customer's own plans (ADR-067 d); {@code fileNumber} comes from their token. Audited. */
    @Transactional
    public List<InstalmentPlan> ownPlans(String fileNumber, int limit, Caller caller) {
        require(caller.grants().holds(CustomerViolations.CUSTOMER_READ));
        if (limit < 1 || limit > 100) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1..100", List.of("VF-071"), "limit");
        }
        audit.record("INSTALMENT_PLANS_LISTED", "TrafficFile", fileNumber, null, Map.of("viewer", "OWNER"), null);
        return plans.byFile(fileNumber, limit);
    }

    // ---- payments (itms.payment.confirmed / receipt-cancelled with item reference <plan>-<nn>)

    /** An instalment was paid: completes the plan on the last one, or makes a LATE plan ACTIVE when paid up. */
    @Transactional
    public boolean onInstalmentPaid(String itemRef) {
        Ref ref = Ref.parse(itemRef);
        InstalmentPlan plan = ref == null ? null : plans.lock(ref.plan()).orElse(null);
        InstalmentPlan.Instalment instalment = plan == null ? null : plan.instalments().stream()
                .filter(i -> i.seq() == ref.seq()).findFirst().orElse(null);
        if (instalment == null || instalment.paid()) {
            return false;
        }
        Instant now = clock.instant();
        plans.instalmentPaid(plan.id(), ref.seq(), now);
        InstalmentPlan after = plans.find(plan.number()).orElseThrow();
        CurrentActor system = CurrentActor.system(SYSTEM);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("planNumber", plan.number());
        detail.put("seq", ref.seq());
        detail.put("amountMinor", instalment.amountMinor());
        detail.put("recipientId", plan.fileNumber());
        audit.recordAs(system, "INSTALMENT_PAID", ENTITY, plan.number(), null, detail, "VF-073");
        events.recordAs(system, "itms.violation.instalment-paid", plan.number(), detail);
        if (after.allPaid()) {
            plans.status(plan.id(), InstalmentPlan.Status.COMPLETED, now);
            audit.recordAs(system, "INSTALMENT_PLAN_COMPLETED", ENTITY, plan.number(),
                    Map.of("status", plan.status().name()), Map.of("status", "COMPLETED"), "VF-073");
            events.recordAs(system, "itms.violation.instalment-plan-completed", plan.number(),
                    Map.of("planNumber", plan.number(), "recipientId", plan.fileNumber()));
            for (String violation : plan.violationNumbers()) {
                violations.onPaid(violation);
            }
        } else if (plan.status() == InstalmentPlan.Status.LATE && !after.anyLapsed(today())) {
            plans.status(plan.id(), InstalmentPlan.Status.ACTIVE, null);
            audit.recordAs(system, "INSTALMENT_PLAN_RESUMED", ENTITY, plan.number(), Map.of("status", "LATE"),
                    Map.of("status", "ACTIVE"), "VF-073");
            for (String violation : plan.violationNumbers()) {
                violations.tryLiftCovered(violation);
            }
        }
        return true;
    }

    /** The receipt of an instalment was cancelled (FN-011): the instalment is unpaid again. */
    @Transactional
    public boolean onInstalmentReceiptCancelled(String itemRef) {
        Ref ref = Ref.parse(itemRef);
        InstalmentPlan plan = ref == null ? null : plans.lock(ref.plan()).orElse(null);
        boolean paid = plan != null && plan.instalments().stream().anyMatch(i -> i.seq() == ref.seq() && i.paid());
        if (!paid) {
            return false;
        }
        plans.instalmentReopened(plan.id(), ref.seq());
        CurrentActor system = CurrentActor.system(SYSTEM);
        if (plan.status() == InstalmentPlan.Status.COMPLETED) {
            // Back to ACTIVE; the lapse job makes it LATE if the reopened instalment is overdue.
            plans.status(plan.id(), InstalmentPlan.Status.ACTIVE, null);
            for (String violation : plan.violationNumbers()) {
                violations.onReceiptCancelled(violation);
            }
        }
        audit.recordAs(system, "INSTALMENT_REOPENED", ENTITY, plan.number(), Map.of("seq", ref.seq(), "paid", true),
                Map.of("seq", ref.seq(), "paid", false), "receipt cancelled");
        return true;
    }

    // ---- missed instalments (VF-073)

    /** ACTIVE plans with an instalment unpaid after its due date plus the grace period. */
    @Transactional(readOnly = true)
    public List<String> lapsedPlans() {
        return plans.activeWithLapsedInstalment(today(), BATCH);
    }

    /**
     * Marks the plan LATE if it still has a lapsed instalment: it no longer covers its violations, so the unpaid-hold
     * job restores their holds (VF-023, VF-073). The customer is told by the notification subscription.
     */
    @Transactional
    public boolean markLateIfLapsed(String number) {
        InstalmentPlan plan = plans.lock(number).orElse(null);
        if (plan == null || plan.status() != InstalmentPlan.Status.ACTIVE || !plan.anyLapsed(today())) {
            return false;
        }
        plans.status(plan.id(), InstalmentPlan.Status.LATE, null);
        CurrentActor system = CurrentActor.system(SYSTEM);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("planNumber", number);
        detail.put("recipientId", plan.fileNumber());
        detail.put("graceDays", plan.graceDays());
        detail.put("violationNumbers", plan.violationNumbers());
        audit.recordAs(system, "INSTALMENT_PLAN_LATE", ENTITY, number, Map.of("status", "ACTIVE"),
                Map.of("status", "LATE"), "VF-073");
        events.recordAs(system, "itms.violation.instalment-missed", number, detail);
        LOG.info("Instalment plan {} is late; unpaid holds of its violations return", number);
        return true;
    }

    // ---- helpers

    private Violation eligible(String number, String fileNumber, Caller caller, boolean own) {
        DomainException notFound = new DomainException(DomainException.Kind.NOT_FOUND, "VIOLATION_NOT_FOUND",
                "violation " + number + " not found", List.of("VF-071"));
        Violation v = repo.findForUpdate(number).orElseThrow(() -> notFound);
        if (own) {
            // a customer's own violations only; any other answers as not found, so numbers cannot be probed
            if (!v.report().responsibleFileNumber().equals(fileNumber)) {
                throw notFound;
            }
        } else {
            require(caller.grants().allowsInRegion(CREATE, v.report().region()));
        }
        if (!v.report().responsibleFileNumber().equals(fileNumber)) {
            throw DomainException.validation("VIOLATION_NOT_OF_FILE",
                    "violation " + number + " is not charged to file " + fileNumber, List.of("VF-071"), number);
        }
        if (v.status() != Violation.Status.OPEN || v.payableId() == null) {
            throw DomainException.conflict("VIOLATION_NOT_PAYABLE", "violation " + number + " is not open for payment",
                    List.of("VF-071"), number);
        }
        if (v.report().source() == Violation.Source.PARTNER || links.byNumber(number).isPresent()) {
            throw DomainException.conflict("VIOLATION_CROSSBORDER", "a cross-border violation cannot be in a plan",
                    List.of("VF-071", "XB-001"), number);
        }
        if (repo.underObjection(v.id())) {
            throw DomainException.conflict("VIOLATION_UNDER_OBJECTION", "violation " + number + " is under objection",
                    List.of("VF-071", "VF-052"), number);
        }
        if (repo.inInstalmentPlan(v.id())) {
            throw DomainException.conflict("VIOLATION_IN_INSTALMENT_PLAN", "violation " + number + " is in a plan",
                    List.of("VF-071"), number);
        }
        return v;
    }

    /** The amount due per financial item and beneficiary ({@code item|beneficiary}), in a stable order. */
    private Map<String, Long> byFinancialItem(Map<Violation, Long> due) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<Violation, Long> e : due.entrySet()) {
            Violation v = e.getKey();
            CatalogueEntry entry = repo.catalogueVersions(v.catalogueCode()).stream()
                    .filter(c -> c.versionNo() == v.catalogueVersion()).findFirst()
                    .orElseThrow(() -> new IllegalStateException("catalogue version of " + v.number() + " missing"));
            out.merge(entry.financialItem() + "|" + entry.beneficiary(), e.getValue(), Long::sum);
        }
        return out;
    }

    /**
     * The lines of the instalment covering cumulative amounts {@code from} to {@code to} of {@code total}: each item
     * gets its share of the cumulative amount (floored), so over the whole plan every item receives exactly its due.
     */
    static List<Ports.Payables.Line> lines(Map<String, Long> byItem, long total, long from, long to,
                                           String description) {
        // Item g's share of a cumulative amount C is floor(C * P_g / T) - floor(C * P_(g-1) / T), with P the prefix sums
        // of the items' dues; the shares add up to C, and at C = T each is exactly the item's due.
        List<Ports.Payables.Line> out = new ArrayList<>();
        long previous = 0;
        for (Map.Entry<String, Long> e : byItem.entrySet()) {
            long prefix = previous + e.getValue();
            long amount = share(to, prefix, previous, total) - share(from, prefix, previous, total);
            previous = prefix;
            if (amount > 0) {
                String[] key = e.getKey().split("\\|", 2);
                out.add(new Ports.Payables.Line(key[0], key[1], description, amount));
            }
        }
        return out;
    }

    private static long share(long cumulative, long prefix, long previous, long total) {
        return Math.floorDiv(Math.multiplyExact(cumulative, prefix), total)
                - Math.floorDiv(Math.multiplyExact(cumulative, previous), total);
    }

    private static Map<String, Object> snapshot(InstalmentPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planNumber", p.number());
        m.put("fileNumber", p.fileNumber());
        m.put("recipientId", p.fileNumber());
        m.put("region", p.region());
        m.put("currency", p.currency());
        m.put("totalMinor", p.totalMinor());
        m.put("months", p.months());
        m.put("graceDays", p.graceDays());
        m.put("violationNumbers", p.violationNumbers());
        m.put("firstDueDate", p.instalments().getFirst().dueDate().toString());
        return m;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), settings.zone());
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with instalment plans", List.of("CC-001"));
        }
    }

    private static DomainException notFound(String number) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "INSTALMENT_PLAN_NOT_FOUND",
                "instalment plan " + number + " not found", List.of("VF-071"));
    }

    /** An instalment's item reference split into plan number and sequence; null when it is not one. */
    record Ref(String plan, int seq) {
        static Ref parse(String itemRef) {
            if (itemRef == null || !itemRef.matches("^IP-[0-9]{4}-[0-9]{9}-[0-9]{2}$")) {
                return null;
            }
            int dash = itemRef.lastIndexOf('-');
            return new Ref(itemRef.substring(0, dash), Integer.parseInt(itemRef.substring(dash + 1)));
        }
    }
}

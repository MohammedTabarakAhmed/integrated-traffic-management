package gov.itms.licensing.application;

import gov.itms.licensing.domain.Channel;
import gov.itms.licensing.domain.DomainException;
import gov.itms.licensing.domain.ExaminationTransfer;
import gov.itms.licensing.domain.Licence;
import gov.itms.licensing.domain.LicenceClassVersion;
import gov.itms.licensing.domain.LicenceRequest;
import gov.itms.licensing.domain.Records;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Driving licences (DL-021…023, DL-036, DL-050, DL-062):
 * <ul>
 *   <li>examination-service hands over each passed examination file ({@value #RECEIVE}); an authorised user may
 *       reverse a transfer, with a reason, while no licence or open request uses it ({@value #REVERSE});</li>
 *   <li>an officer asks for a new licence from the person's open transfers ({@value #ISSUE}): no licence in force,
 *       no blocking hold, a class rule in force for the customer category; the fee is quoted and the payable
 *       registered, and the licence is issued when payment-service confirms payment;</li>
 *   <li>the issued licence gets the next number of the region (DL-061), one class per transfer with its own issue
 *       date (DL-021), automatic-only when tested in an automatic vehicle of a class that records the gearbox
 *       (DL-022), the conditions asked for (DL-023), the shortest validity of its classes for the customer category,
 *       never beyond a linked residency (DL-062), and, for a first licence, a probation period (DL-050);</li>
 *   <li>conditions are added or removed later with a reason ({@value #CONDITION}).</li>
 * </ul>
 * Every change is audited and kept in the licence history; issue publishes {@code itms.licence.issued}.
 */
public class LicenceService {

    public static final String RECEIVE = "licensing.transfer.receive";
    public static final String REVERSE = "licensing.transfer.reverse";
    public static final String ISSUE = "licensing.licence.issue";
    public static final String CONDITION = "licensing.licence.condition";
    public static final String READ = "licensing.licence.read";
    static final String ENTITY = "Licence";
    static final String TRANSFER_ENTITY = "ExaminationTransfer";
    static final String SYSTEM = "licensing-payment-consumer";
    static final int MAX_TRANSFERS = 10;

    private final LicenceRepository repo;
    private final LicenceClassService classes;
    private final LicenceNumberService numbers;
    private final Ports.FeeQuotes fees;
    private final Ports.Payables payables;
    private final Ports.Eligibility eligibility;
    private final Ports.TrafficFiles trafficFiles;
    private final LicensingRules rules;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public LicenceService(LicenceRepository repo, LicenceClassService classes, LicenceNumberService numbers,
                          Ports.FeeQuotes fees, Ports.Payables payables, Ports.Eligibility eligibility,
                          Ports.TrafficFiles trafficFiles, LicensingRules rules, AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.repo = repo;
        this.classes = classes;
        this.numbers = numbers;
        this.fees = fees;
        this.payables = payables;
        this.eligibility = eligibility;
        this.trafficFiles = trafficFiles;
        this.rules = rules;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /**
     * What an officer asks for when issuing a new licence (DL-050).
     *
     * @param customerCategory   optional; when given it must match the traffic file's (DL-062, Q-076 a)
     * @param residencyExpiresOn optional; when given it must match the traffic file's residency end
     */
    public record IssueSpec(String trafficFileNumber, String region, List<UUID> transferIds, String customerCategory,
                            LocalDate residencyExpiresOn, List<Licence.Condition> conditions,
                            Records.Delivery delivery, String address) {
        public IssueSpec {
            transferIds = transferIds == null ? List.of() : List.copyOf(transferIds);
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
            delivery = delivery == null ? Records.Delivery.CENTRE : delivery;
            address = Records.address(address);
        }
    }

    /** A request completed by payment: makes the change the request asks for (DL-051…DL-057). */
    public interface PaidHandler {

        boolean handles(LicenceRequest.Kind kind);

        /** @return the licence created or changed, or null when the request creates no licence (a permit) */
        Licence completed(LicenceRequest request, CurrentActor system);
    }

    private final List<PaidHandler> handlers = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addHandler(PaidHandler handler) {
        handlers.add(handler);
    }

    // ---- examination transfers (DL-036)

    @Transactional
    public ExaminationTransfer receive(ExaminationTransfer.Spec spec, Caller caller) {
        LicenceClassService.channelOf(caller);
        requireIn(caller, RECEIVE, spec.region());
        repo.lockHolder(spec.trafficFileNumber());
        Optional<ExaminationTransfer> existing = repo.transferOf(spec.examinationFileRef(), spec.classCode());
        if (existing.isPresent()) {
            throw DomainException.conflict("TRANSFER_EXISTS", "examination file " + spec.examinationFileRef()
                    + " was already transferred for class " + spec.classCode(), List.of("DL-036"),
                    existing.get().id().toString());
        }
        Instant now = clock.instant();
        ExaminationTransfer t = ExaminationTransfer.receive(UUID.randomUUID(), spec, caller.actor(), now, today());
        repo.insertTransfer(t);
        audit.record("EXAMINATION_TRANSFER_RECEIVED", TRANSFER_ENTITY, t.id().toString(), null, snapshot(t), null);
        return t;
    }

    /** DL-036: reversed with a reason while no licence was issued from it and no open request waits on it. */
    @Transactional
    public ExaminationTransfer reverse(UUID id, String reason, Caller caller) {
        LicenceClassService.channelOf(caller);
        ExaminationTransfer found = repo.transfer(id).orElseThrow(() -> transferNotFound(id));
        requireIn(caller, REVERSE, found.spec().region());
        repo.lockHolder(found.spec().trafficFileNumber());
        ExaminationTransfer t = repo.transferForUpdate(id).orElseThrow(() -> transferNotFound(id));
        if (repo.openRequests(t.spec().trafficFileNumber(), LicenceRequest.Kind.ISSUE).stream()
                .anyMatch(r -> r.transferIds().contains(id))) {
            throw DomainException.conflict("TRANSFER_IN_REQUEST", "a licence request waiting for payment uses this"
                    + " examination transfer", List.of("DL-036"));
        }
        Map<String, Object> before = snapshot(t);
        t.reverse(caller.actor(), reason, clock.instant());
        repo.updateTransfer(t);
        audit.record("EXAMINATION_TRANSFER_REVERSED", TRANSFER_ENTITY, id.toString(), before, snapshot(t),
                t.reversalReason());
        events.record("itms.licensing.transfer-reversed", t.spec().examinationFileRef(), snapshot(t));
        return t;
    }

    @Transactional(readOnly = true)
    public ExaminationTransfer transfer(UUID id, Caller caller) {
        ExaminationTransfer t = repo.transfer(id).orElseThrow(() -> transferNotFound(id));
        requireIn(caller, READ, t.spec().region());
        return t;
    }

    @Transactional(readOnly = true)
    public List<ExaminationTransfer> transfers(String trafficFileNumber, Caller caller) {
        LicenceClassService.require(caller, READ);
        return repo.transfersOf(trafficFileNumber).stream()
                .filter(t -> caller.grants().allowsInRegion(READ, t.spec().region())).toList();
    }

    // ---- new licence (DL-050)

    /**
     * Checks the request, quotes the fee and registers the payable; the licence itself is issued by
     * {@link #onPaid} once payment-service confirms the payment.
     */
    @Transactional
    public LicenceRequest requestIssue(IssueSpec spec, Caller caller) {
        Channel channel = LicenceClassService.channelOf(caller);
        requireIn(caller, ISSUE, spec.region());
        if (spec.transferIds().isEmpty() || spec.transferIds().size() > MAX_TRANSFERS
                || new HashSet<>(spec.transferIds()).size() != spec.transferIds().size()) {
            throw DomainException.validation("LIST_SIZE_INVALID", "transferIds needs 1 to " + MAX_TRANSFERS
                    + " different entries", List.of("DL-050"), "transferIds", 1, MAX_TRANSFERS);
        }
        repo.lockHolder(spec.trafficFileNumber());
        LocalDate today = today();
        List<ExaminationTransfer> transfers = openTransfers(spec.trafficFileNumber(), spec.transferIds());
        requireNoLicenceInForce(spec.trafficFileNumber());
        if (!repo.openRequests(spec.trafficFileNumber(), LicenceRequest.Kind.ISSUE).isEmpty()) {
            throw DomainException.conflict("REQUEST_OPEN", "a licence request of this person waits for payment",
                    List.of("DL-050"));
        }
        requireKnownConditions(spec.conditions());
        Ports.TrafficFiles.Holder holder = holder(spec.trafficFileNumber(), spec.customerCategory(),
                spec.residencyExpiresOn());
        terms(classCodes(transfers), holder.customerCategory(), holder.residencyExpiresOn(), today, today);
        String serviceCode = rules.serviceCode(LicenceRequest.Kind.ISSUE);
        requireNoHolds(serviceCode, spec.trafficFileNumber(), null, channel);
        Ports.FeeQuotes.Quote quote = fees.quote(serviceCode, spec.region(), holder.customerCategory(), null,
                Map.of("CLASSES", transfers.size()), null);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("customerCategory", holder.customerCategory());
        if (holder.residencyExpiresOn() != null) {
            payload.put("residencyExpiresOn", holder.residencyExpiresOn().toString());
        }
        for (Licence.Condition c : spec.conditions()) {
            payload.put("condition." + c.code(), c.note() == null ? "" : c.note());
        }
        delivery(payload, spec.delivery(), spec.address());
        return open(LicenceRequest.Kind.ISSUE, null, spec.trafficFileNumber(), spec.region(), quote, payload,
                spec.transferIds(), channel, caller.actor().id(), null);
    }

    /**
     * Completes a request whose payable payment-service confirmed ({@code itms.payment.confirmed}). Repeats and
     * other items are ignored, so a re-delivered event changes nothing (INT-005).
     *
     * @return whether a request was completed
     */
    @Transactional
    public boolean onPaid(String itemRef) {
        UUID requestId = LicenceRequest.idOf(itemRef);
        if (requestId == null) {
            return false;
        }
        Optional<LicenceRequest> found = repo.requestForUpdate(requestId);
        if (found.isEmpty() || found.get().status() != LicenceRequest.Status.AWAITING_PAYMENT) {
            return false;
        }
        LicenceRequest r = found.get();
        CurrentActor system = CurrentActor.system(SYSTEM);
        Licence licence = r.kind() == LicenceRequest.Kind.ISSUE ? issue(r, system)
                : handlers.stream().filter(h -> h.handles(r.kind())).findFirst()
                .orElseThrow(() -> new IllegalStateException("no handler for " + r.kind())).completed(r, system);
        repo.updateRequest(r.completed(licence == null ? r.licenceId() : licence.id(), clock.instant()));
        return true;
    }

    private Licence issue(LicenceRequest r, CurrentActor system) {
        repo.lockHolder(r.trafficFileNumber());
        Instant now = clock.instant();
        List<ExaminationTransfer> transfers = new ArrayList<>();
        List<Grant> grants = new ArrayList<>();
        for (UUID id : r.transferIds()) {
            ExaminationTransfer t = repo.transferForUpdate(id).orElseThrow(() -> transferNotFound(id));
            t.requireTransferred();
            transfers.add(t);
            grants.add(new Grant(t.spec().classCode(), t.spec().gearbox() == ExaminationTransfer.Gearbox.AUTOMATIC,
                    t.id()));
        }
        boolean first = repo.licencesOf(r.trafficFileNumber()).isEmpty();
        Licence licence = createLicence(r, grants, first, "ISSUED", "itms.licence.issued", system);
        for (ExaminationTransfer t : transfers) {
            t.usedBy(licence.id(), now);
            repo.updateTransfer(t);
        }
        return licence;
    }

    /** A class granted on a new licence: tested in an automatic vehicle or not, and from which transfer. */
    record Grant(String classCode, boolean automatic, UUID transferId) {
    }

    /**
     * Creates a licence for a paid request (DL-050, DL-054): next number of the region (DL-061), one class per grant
     * with today's issue date (DL-021), automatic-only where the class records the gearbox (DL-022), the request's
     * conditions (DL-023), validity per customer category capped at residency (DL-062), probation when asked
     * (DL-050), a first card, history, audit and the event.
     */
    Licence createLicence(LicenceRequest r, List<Grant> grants, boolean probationary, String historyEvent,
                          String eventType, CurrentActor system) {
        Instant now = clock.instant();
        LocalDate today = today();
        requireNoLicenceInForce(r.trafficFileNumber());
        LocalDate residency = r.value("residencyExpiresOn") == null ? null
                : LocalDate.parse(r.value("residencyExpiresOn"));
        Terms terms = terms(grants.stream().map(Grant::classCode).toList(), r.value("customerCategory"), residency,
                today, today);
        LocalDate probation = probationary && rules.probationMonths() > 0
                ? today.plusMonths(rules.probationMonths()) : null;
        List<Licence.Condition> conditions = new ArrayList<>();
        r.payload().forEach((k, v) -> {
            if (k.startsWith("condition.")) {
                conditions.add(new Licence.Condition(k.substring("condition.".length()), v.isEmpty() ? null : v,
                        today));
            }
        });
        UUID id = UUID.randomUUID();
        String number = numbers.allocate(r.region(), id.toString(), system).licenceNumber();
        List<Licence.LicenceClass> licenceClasses = new ArrayList<>();
        for (int i = 0; i < grants.size(); i++) {
            Grant g = grants.get(i);
            LicenceClassVersion v = terms.rules().get(i).version();
            licenceClasses.add(new Licence.LicenceClass(g.classCode(), v.id(), today, Licence.ClassStatus.ACTIVE,
                    v.spec().gearboxRecorded() && g.automatic(), g.transferId()));
        }
        Licence licence = Licence.issue(id, number, r.trafficFileNumber(), r.region(), r.value("customerCategory"),
                today, terms.expiresOn(), probation, residency, licenceClasses, conditions);
        repo.insertLicence(licence);
        Map<String, Object> detail = snapshot(licence);
        detail.put("requestId", r.id().toString());
        Records.Card card = issueCard(licence, Records.CardReason.ISSUE, r, now);
        detail.put("cardSerial", card.serial());
        repo.history(id, historyEvent, system.userId(), detail, now);
        audit.recordAs(system, "LICENCE_" + historyEvent, ENTITY, number, null, detail, null);
        events.recordAs(system, eventType, number, detail);
        return licence;
    }

    // ---- conditions (DL-023)

    @Transactional
    public Licence addCondition(UUID licenceId, Licence.Condition condition, String reason, Caller caller) {
        return changeConditions(licenceId, reason, caller, "CONDITION_ADDED", l -> {
            requireKnownConditions(List.of(condition));
            l.addCondition(condition);
        }, condition.code());
    }

    @Transactional
    public Licence removeCondition(UUID licenceId, String code, String reason, Caller caller) {
        return changeConditions(licenceId, reason, caller, "CONDITION_REMOVED", l -> l.removeCondition(code), code);
    }

    private Licence changeConditions(UUID licenceId, String reason, Caller caller, String event,
                                     java.util.function.Consumer<Licence> change, String code) {
        LicenceClassService.channelOf(caller);
        String why = reasonOf(reason, "DL-023");
        Licence licence = licenceInRegion(licenceId, CONDITION, caller, true);
        Map<String, Object> before = snapshot(licence);
        change.accept(licence);
        repo.updateLicence(licence);
        Map<String, Object> after = snapshot(licence);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("condition", code);
        detail.put("reason", why);
        repo.history(licence.id(), event, caller.actor().id(), detail, clock.instant());
        audit.record("LICENCE_" + event, ENTITY, licence.licenceNumber(), before, after, why);
        events.record("itms.licence.changed", licence.licenceNumber(), after);
        return licence;
    }

    // ---- reads

    @Transactional(readOnly = true)
    public Licence licence(UUID id, Caller caller) {
        return licenceInRegion(id, READ, caller, false);
    }

    @Transactional(readOnly = true)
    public Licence licenceByNumber(String licenceNumber, Caller caller) {
        Licence l = repo.licenceByNumber(licenceNumber).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "LICENCE_NOT_FOUND", "licence " + licenceNumber + " does not exist",
                List.of("DL-021")));
        requireIn(caller, READ, l.region());
        return l;
    }

    /** The person's licences, newest first, in the regions the caller's grants reach. */
    @Transactional(readOnly = true)
    public List<Licence> licencesOf(String trafficFileNumber, Caller caller) {
        LicenceClassService.require(caller, READ);
        return repo.licencesOf(trafficFileNumber).stream()
                .filter(l -> caller.grants().allowsInRegion(READ, l.region())).toList();
    }

    @Transactional(readOnly = true)
    public List<LicenceRepository.HistoryEntry> history(UUID licenceId, Caller caller) {
        licenceInRegion(licenceId, READ, caller, false);
        return repo.history(licenceId);
    }

    @Transactional(readOnly = true)
    public LicenceRequest request(UUID id, Caller caller) {
        LicenceRequest r = repo.request(id).orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                "REQUEST_NOT_FOUND", "licensing request " + id + " does not exist", List.of("DL-050")));
        requireIn(caller, READ, r.region());
        return r;
    }

    /** A licence's cards, newest first (DL-060). */
    @Transactional(readOnly = true)
    public List<Records.Card> cards(UUID licenceId, Caller caller) {
        licenceInRegion(licenceId, READ, caller, false);
        return repo.cards(licenceId);
    }

    /** The condition codes that may be recorded (DL-023). */
    public List<String> conditionCodes() {
        return rules.sortedConditionCodes();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    // ---- shared by the licensing transactions

    /** The validity terms of a licence with these classes (DL-062). */
    record Terms(List<LicenceClassService.ResolvedRule> rules, LocalDate expiresOn) {
    }

    /**
     * The holder's customer category and residency end, read from the traffic file (DL-062; Q-076 a: the traffic
     * file owns them, officer entry is no longer the source). A value the officer gives must agree with the file.
     */
    Ports.TrafficFiles.Holder holder(String trafficFileNumber, String givenCategory, LocalDate givenResidency) {
        Ports.TrafficFiles.Holder h = trafficFiles.holder(trafficFileNumber).orElseThrow(() ->
                DomainException.validation("TRAFFIC_FILE_UNKNOWN", "the traffic file does not exist",
                        List.of("DL-062")));
        if (givenCategory != null && !givenCategory.equals(h.customerCategory())) {
            throw DomainException.validation("CUSTOMER_CATEGORY_MISMATCH", "the customer category differs from the "
                    + "traffic file's", List.of("DL-062"));
        }
        if (givenResidency != null && !givenResidency.equals(h.residencyExpiresOn())) {
            throw DomainException.validation("RESIDENCY_MISMATCH", "the residency end differs from the traffic "
                    + "file's", List.of("DL-062"));
        }
        return h;
    }

    /**
     * Resolves each class's rule for the customer category on {@code today}: the licence lasts the shortest
     * validity among its classes from {@code from} and, when a class links validity to residency, never beyond the
     * residency.
     */
    Terms terms(List<String> classCodes, String customerCategory, LocalDate residencyExpiresOn, LocalDate from,
                LocalDate today) {
        if (customerCategory == null || !customerCategory.matches("^[A-Z][A-Z0-9_]{0,39}$")) {
            throw DomainException.validation("FIELD_INVALID", "customerCategory is missing or malformed",
                    List.of("DL-062"), "customerCategory");
        }
        List<LicenceClassService.ResolvedRule> resolved = classCodes.stream()
                .map(c -> classes.ruleOn(c, customerCategory, today)).toList();
        int months = resolved.stream().mapToInt(r -> r.rule().validityMonths()).min().orElseThrow();
        LocalDate expires = from.plusMonths(months);
        if (resolved.stream().anyMatch(r -> r.rule().residencyLinked())) {
            if (residencyExpiresOn == null) {
                throw DomainException.validation("RESIDENCY_EXPIRY_REQUIRED", "the licence validity is linked to the"
                        + " holder's residency, but the traffic file has no residency end", List.of("DL-062"));
            }
            if (!residencyExpiresOn.isAfter(today)) {
                throw DomainException.validation("RESIDENCY_ENDED", "the holder's residency has ended",
                        List.of("DL-062"));
            }
            if (residencyExpiresOn.isBefore(expires)) {
                expires = residencyExpiresOn;
            }
        }
        return new Terms(resolved, expires);
    }

    /** Whether any of the classes links validity to residency for the customer category (DL-062). */
    boolean residencyLinked(List<String> classCodes, String customerCategory, LocalDate today) {
        return classCodes.stream().anyMatch(c -> classes.ruleOn(c, customerCategory, today).rule().residencyLinked());
    }

    private static List<String> classCodes(List<ExaminationTransfer> transfers) {
        return transfers.stream().map(t -> t.spec().classCode()).toList();
    }

    private List<ExaminationTransfer> openTransfers(String trafficFileNumber, List<UUID> ids) {
        List<ExaminationTransfer> transfers = new ArrayList<>();
        Set<String> classCodes = new HashSet<>();
        for (UUID id : ids) {
            ExaminationTransfer t = repo.transfer(id).orElseThrow(() -> transferNotFound(id));
            t.requireTransferred();
            if (!t.spec().trafficFileNumber().equals(trafficFileNumber)) {
                throw DomainException.validation("TRANSFER_OTHER_PERSON", "examination transfer " + id
                        + " belongs to another traffic file", List.of("DL-050"));
            }
            if (!classCodes.add(t.spec().classCode())) {
                throw DomainException.validation("LICENCE_CLASS_DUPLICATE", t.spec().classCode() + " is given twice",
                        List.of("DL-021"), t.spec().classCode());
            }
            transfers.add(t);
        }
        transfers.sort(Comparator.comparing(t -> t.spec().classCode()));
        return transfers;
    }

    private void requireNoLicenceInForce(String trafficFileNumber) {
        if (repo.licencesOf(trafficFileNumber).stream().anyMatch(l -> l.status() == Licence.Status.ACTIVE
                || l.status() == Licence.Status.SUSPENDED)) {
            throw DomainException.conflict("LICENCE_EXISTS", "this person already holds a licence; add the class to"
                    + " it instead (DL-053)", List.of("DL-050", "DL-053"));
        }
    }

    void requireKnownConditions(List<Licence.Condition> conditions) {
        for (Licence.Condition c : conditions) {
            if (!rules.conditionCodes().contains(c.code())) {
                throw DomainException.validation("CONDITION_UNKNOWN", "condition " + c.code() + " is not in the list",
                        List.of("DL-023"), c.code());
            }
        }
    }

    /** CC-032: holds on the person (and the licence, when there is one) that block {@code serviceCode}. */
    void requireNoHolds(String serviceCode, String trafficFileNumber, String licenceNumber, Channel channel) {
        List<Ports.Eligibility.Subject> subjects = new ArrayList<>();
        subjects.add(new Ports.Eligibility.Subject("TRAFFIC_FILE", trafficFileNumber));
        if (licenceNumber != null) {
            subjects.add(new Ports.Eligibility.Subject("DRIVING_LICENCE", licenceNumber));
        }
        List<Ports.Eligibility.Block> blocks = eligibility.blocks(serviceCode, subjects, channel.name());
        if (!blocks.isEmpty()) {
            throw new EligibilityBlockedException(serviceCode, blocks);
        }
    }

    /**
     * Registers the payable of a quoted fee and keeps the request until payment is confirmed; {@code as} is the system
     * actor for requests opened by a job (DL-065), null for a person's request.
     */
    LicenceRequest open(LicenceRequest.Kind kind, UUID licenceId, String trafficFileNumber, String region,
                        Ports.FeeQuotes.Quote quote, Map<String, String> payload, List<UUID> transferIds,
                        Channel channel, String actorId, CurrentActor as) {
        UUID id = UUID.randomUUID();
        UUID payableId = payables.register(LicenceRequest.itemRef(id), trafficFileNumber, region, quote.currency(),
                quote.lines());
        LicenceRequest r = new LicenceRequest(id, kind, LicenceRequest.Status.AWAITING_PAYMENT, licenceId,
                trafficFileNumber, region, quote.id(), payableId, quote.total(), quote.currency(), payload,
                transferIds, actorId, channel, clock.instant(), null);
        repo.insertRequest(r);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", id.toString());
        detail.put("kind", kind.name());
        detail.put("trafficFileNumber", trafficFileNumber);
        detail.put("amount", quote.total());
        detail.put("currency", quote.currency());
        detail.putAll(payload);
        if (licenceId != null) {
            repo.history(licenceId, kind.name() + "_REQUESTED", actorId, detail, clock.instant());
        }
        if (as == null) {
            audit.record("LICENCE_" + kind.name() + "_REQUESTED", "LicenceRequest", id.toString(), null, detail, null);
        } else {
            audit.recordAs(as, "LICENCE_" + kind.name() + "_REQUESTED", "LicenceRequest", id.toString(), null, detail,
                    null);
        }
        return r;
    }

    /** One request at a time per licence. */
    void requireNoOpenRequest(Licence l) {
        if (!repo.openRequestsOf(l.id()).isEmpty()) {
            throw DomainException.conflict("REQUEST_OPEN", "another request of this licence waits for payment",
                    List.of("DL-051", "DL-052", "DL-057"));
        }
    }

    // ---- cards (DL-052, DL-060)

    static void delivery(Map<String, String> payload, Records.Delivery delivery, String address) {
        payload.put("delivery", (delivery == null ? Records.Delivery.CENTRE : delivery).name());
        if (address != null) {
            payload.put("address", address);
        }
    }

    /** A new card for the licence with the delivery the request asked for; earlier live cards are cancelled. */
    Records.Card issueCard(Licence licence, Records.CardReason reason, LicenceRequest r, Instant now) {
        String delivery = r == null ? null : r.value("delivery");
        return issueCard(licence, reason, delivery == null ? Records.Delivery.CENTRE
                : Records.Delivery.valueOf(delivery), r == null ? null : r.value("address"), now);
    }

    Records.Card issueCard(Licence licence, Records.CardReason reason, Records.Delivery delivery, String address,
                           Instant now) {
        cancelCards(licence, now);
        Records.Card card = new Records.Card(UUID.randomUUID(), licence.id(), repo.nextCardSerial(licence.id()),
                reason, delivery, address, Records.CardStatus.PENDING_PRINT, null, now, now);
        repo.insertCard(card);
        return card;
    }

    /** Cancels every live card of the licence at once (DL-052); returns how many. */
    int cancelCards(Licence licence, Instant now) {
        int n = 0;
        for (Records.Card c : repo.cards(licence.id())) {
            if (c.live()) {
                repo.updateCard(c.moveTo(Records.CardStatus.CANCELLED, null, now));
                n++;
            }
        }
        return n;
    }

    LicensingRules rules() {
        return rules;
    }

    Ports.FeeQuotes fees() {
        return fees;
    }

    LicenceRepository repo() {
        return repo;
    }

    LicenceClassService classes() {
        return classes;
    }

    Licence licenceInRegion(UUID id, String permission, Caller caller, boolean forUpdate) {
        Licence l = (forUpdate ? repo.licenceForUpdate(id) : repo.licence(id)).orElseThrow(() -> licenceNotFound(id));
        requireIn(caller, permission, l.region());
        return l;
    }

    static boolean automaticOnly(ExaminationTransfer t, LicenceClassVersion v) {
        return v.spec().gearboxRecorded() && t.spec().gearbox() == ExaminationTransfer.Gearbox.AUTOMATIC;
    }

    static String reasonOf(String reason, String ref) {
        String v = reason == null ? "" : reason.strip();
        if (v.length() < 3 || v.length() > 500 || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of(ref, "CC-120"));
        }
        return v;
    }

    static void requireIn(Caller caller, String permission, String region) {
        if (!caller.grants().allowsInRegion(permission, region)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with licences of region " + region, List.of("CC-001"));
        }
    }

    static DomainException licenceNotFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "LICENCE_NOT_FOUND",
                "licence " + id + " does not exist", List.of("DL-021"));
    }

    private static DomainException transferNotFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "TRANSFER_NOT_FOUND",
                "examination transfer " + id + " does not exist", List.of("DL-036"));
    }

    static Map<String, Object> snapshot(ExaminationTransfer t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("examinationFileRef", t.spec().examinationFileRef());
        m.put("trafficFileNumber", t.spec().trafficFileNumber());
        m.put("classCode", t.spec().classCode());
        m.put("gearbox", t.spec().gearbox().name());
        m.put("passedOn", t.spec().passedOn().toString());
        m.put("region", t.spec().region());
        m.put("status", t.status().name());
        if (t.licenceId() != null) {
            m.put("licenceId", t.licenceId().toString());
        }
        return m;
    }

    static Map<String, Object> snapshot(Licence l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("licenceId", l.id().toString());
        m.put("licenceNumber", l.licenceNumber());
        m.put("trafficFileNumber", l.trafficFileNumber());
        m.put("region", l.region());
        m.put("customerCategory", l.customerCategory());
        m.put("status", l.status().name());
        m.put("issuedOn", l.issuedOn().toString());
        m.put("expiresOn", l.expiresOn().toString());
        m.put("probationUntil", l.probationUntil() == null ? null : l.probationUntil().toString());
        m.put("suspendedUntil", l.suspendedUntil() == null ? null : l.suspendedUntil().toString());
        m.put("classes", l.classes().stream().map(c -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("classCode", c.classCode());
            cm.put("issuedOn", c.issuedOn().toString());
            cm.put("status", c.status().name());
            cm.put("automaticOnly", c.automaticOnly());
            return cm;
        }).toList());
        m.put("conditions", l.conditions().stream().map(Licence.Condition::code).toList());
        return m;
    }
}

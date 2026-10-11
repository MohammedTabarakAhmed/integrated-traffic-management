package gov.itms.crossborder.application;

import gov.itms.crossborder.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partner-state exchange (XB-001..XB-008; DL-064 licence data):
 * <ul>
 *   <li>partner settings and code mappings change only through maker-checker proposals (XB-007, XB-008);</li>
 *   <li>violations recorded here against partner-registered vehicles are sent; violations received are loaded by
 *       violation-service against the local vehicle and owner (XB-001, XB-002);</li>
 *   <li>a payment in either state closes the violation in both: a local payment queues a payment notice to the
 *       partner, a partner's notice closes the local violation (XB-003, UAT-5);</li>
 *   <li>objections travel to the issuing state and its decision comes back and is applied (XB-005);</li>
 *   <li>accident, permit and licence data are exchanged where the agreement allows (XB-006, DL-064);</li>
 *   <li>outbound messages are delivered near real time or in a daily batch per partner, with acknowledgements and
 *       bounded retries (XB-008); every message and status change is kept (XB-007);</li>
 *   <li>settlement statements state what each state owes the other after the collecting state's commission
 *       (XB-004).</li>
 * </ul>
 * Calls from consumers and schedulers pass {@code actor == null} and are recorded as this service's system actor.
 */
public class CrossborderService {

    static final String SYSTEM = "crossborder-exchange";
    private static final Set<String> MODES = Set.of("REALTIME", "DAILY_BATCH");
    private static final Set<String> DATA_TYPES = Set.of("ACCIDENT", "PERMIT", "LICENCE");
    private static final String CODE = "^[A-Z0-9]{2,12}$";

    /** Settings: this state's code in envelopes, delivery attempts and back-off. */
    public record Settings(String localState, int maxAttempts, Duration retryBase, Duration retryMax) {
        public Settings {
            if (localState == null || !localState.matches(CODE)) {
                throw new IllegalArgumentException("itms.crossborder.local-state must be 2..12 capitals or digits");
            }
            if (maxAttempts < 1 || maxAttempts > 8) {
                throw new IllegalArgumentException("itms.crossborder.max-attempts must be 1..8");
            }
            if (retryBase.isNegative() || retryBase.isZero() || retryMax.compareTo(retryBase) < 0
                    || retryMax.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("itms.crossborder.retry-base/retry-max must be positive, max ≤ 1 day");
            }
        }
    }

    public record Partner(String code, String name, String mode, int commissionBasisPoints, String status,
                          String endpointUrl, boolean allowsAccident, boolean allowsPermit, boolean allowsLicence) {
        public boolean allows(String dataType) {
            return switch (dataType) {
                case "ACCIDENT" -> allowsAccident;
                case "PERMIT" -> allowsPermit;
                case "LICENCE" -> allowsLicence;
                default -> false;
            };
        }
    }

    public record Message(UUID id, String partner, String externalId, String type, String direction, String status,
                          String localReference, String externalCode, String mappedCode, String vehiclePlate,
                          String ownerFile, String currency, long amountMinor, Instant occurredAt,
                          Instant receivedAt, String detail, UUID relatedMessageId, Map<String, Object> payload,
                          Instant paidAt, String paidIn, String paymentReference, Integer commissionBasisPoints) {
        public Message {
            payload = payload == null ? null : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
        }
    }

    /** The amounts the next statement for a partner and currency would contain (XB-004). */
    public record Settlement(String partner, Instant from, Instant to, String currency, long collectedHereMinor,
                             long commissionHereMinor, long owedToPartnerMinor, long collectedByPartnerMinor,
                             long commissionPartnerMinor, long owedByPartnerMinor, long netMinor, int messageCount) { }

    public record StatementView(Ports.Statement statement, List<Ports.StatementLine> lines) {
        public StatementView {
            lines = lines == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(lines));
        }
    }

    private final Ports.ExchangeRepository repo;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Settings settings;
    private final Clock clock;

    public CrossborderService(Ports.ExchangeRepository repo, AuditRecorder audit, DomainEventRecorder events,
                              Settings settings, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    // ---- maker-checker configuration (XB-007, XB-008)

    @Transactional
    public Ports.Proposal proposePartner(String code, String name, String mode, int commissionBps, String endpointUrl,
                                         boolean allowsAccident, boolean allowsPermit, boolean allowsLicence,
                                         String status, String reason, String actor) {
        String s = status == null ? "ACTIVE" : status;
        if (code == null || !code.matches(CODE) || name == null || name.isBlank() || name.length() > 120
                || !MODES.contains(mode) || commissionBps < 0 || commissionBps > 10000
                || !("ACTIVE".equals(s) || "SUSPENDED".equals(s))
                || (endpointUrl != null && !endpointUrl.matches("^https?://\\S{1,490}$"))) {
            throw invalid("PARTNER_SETTINGS_INVALID", "partner code, mode, commission, endpoint or status is invalid",
                    "XB-004", "XB-008");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("name", name);
        payload.put("mode", mode);
        payload.put("commissionBasisPoints", commissionBps);
        payload.put("endpointUrl", endpointUrl);
        payload.put("allowsAccident", allowsAccident);
        payload.put("allowsPermit", allowsPermit);
        payload.put("allowsLicence", allowsLicence);
        payload.put("status", s);
        return propose("PARTNER", code, payload, reason, actor, "XB-008");
    }

    @Transactional
    public Ports.Proposal proposeMapping(String partner, String domain, String local, String external, String reason,
                                         String actor) {
        repo.partner(partner).orElseThrow(() -> missing("PARTNER_NOT_FOUND", "partner state is not configured",
                "XB-007"));
        if (domain == null || !domain.matches("^[A-Z][A-Z0-9_]{1,39}$") || local == null || local.isBlank()
                || local.length() > 80 || external == null || external.isBlank() || external.length() > 80) {
            throw invalid("CODE_MAPPING_INVALID", "domain, local and partner codes are required", "XB-007");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partner", partner);
        payload.put("domain", domain);
        payload.put("localCode", local);
        payload.put("externalCode", external);
        return propose("MAPPING", partner + ":" + domain + ":" + local, payload, reason, actor, "XB-007");
    }

    private Ports.Proposal propose(String kind, String subject, Map<String, Object> payload, String reason,
                                   String actor, String requirement) {
        if (reason == null || reason.strip().length() < 3 || reason.length() > 500) {
            throw invalid("REASON_INVALID", "give a reason of 3 to 500 characters", requirement);
        }
        Ports.Proposal p = new Ports.Proposal(UUID.randomUUID(), kind, subject, payload, "PROPOSED", actor,
                clock.instant(), reason.strip(), null, null, null);
        try {
            repo.insertProposal(p);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw conflict("PROPOSAL_OPEN", "a change to this setting is already awaiting approval", requirement);
        }
        audit.record("CROSSBORDER_CHANGE_PROPOSED", "ConfigProposal", p.id().toString(), null,
                Map.of("kind", kind, "subject", subject, "payload", payload), p.reason());
        return p;
    }

    @Transactional(readOnly = true)
    public List<Ports.Proposal> openProposals(int limit) {
        return repo.openProposals(page(limit));
    }

    /** The checker approves or rejects; the maker never decides their own change (CC maker-checker). */
    @Transactional
    public Ports.Proposal decideProposal(UUID id, boolean approve, String reason, String actor) {
        Ports.Proposal p = repo.proposalForUpdate(id).orElseThrow(() -> missing("PROPOSAL_NOT_FOUND",
                "proposal does not exist", "XB-007"));
        if (!"PROPOSED".equals(p.status())) {
            throw conflict("PROPOSAL_DECIDED", "the proposal has already been decided", "XB-007");
        }
        if (p.proposedBy().equals(actor)) {
            throw conflict("FOUR_EYES_REQUIRED", "the maker cannot approve their own change", "XB-007");
        }
        if (reason == null || reason.strip().length() < 3 || reason.length() > 500) {
            throw invalid("REASON_INVALID", "give a reason of 3 to 500 characters", "XB-007");
        }
        Instant now = clock.instant();
        if (approve) {
            Map<String, Object> v = p.payload();
            if ("PARTNER".equals(p.kind())) {
                Partner partner = new Partner(str(v, "code"), str(v, "name"), str(v, "mode"),
                        ((Number) v.get("commissionBasisPoints")).intValue(), str(v, "status"), str(v, "endpointUrl"),
                        bool(v, "allowsAccident"), bool(v, "allowsPermit"), bool(v, "allowsLicence"));
                Optional<Partner> before = repo.partner(partner.code());
                repo.savePartner(partner, p.proposedBy(), now);
                audit.record("CROSSBORDER_PARTNER_CONFIGURED", "PartnerState", partner.code(),
                        before.orElse(null), partner, reason.strip());
            } else {
                String partner = str(v, "partner");
                Optional<String> before = repo.externalCode(partner, str(v, "domain"), str(v, "localCode"));
                repo.saveMapping(partner, str(v, "domain"), str(v, "localCode"), str(v, "externalCode"),
                        p.proposedBy(), now);
                audit.record("CROSSBORDER_CODE_MAPPED", "CodeMapping", p.subject(),
                        before.map(b -> Map.of("externalCode", b)).orElse(null),
                        Map.of("externalCode", str(v, "externalCode")), reason.strip());
            }
        }
        String status = approve ? "APPROVED" : "REJECTED";
        repo.decideProposal(id, status, actor, now, reason.strip());
        audit.record("CROSSBORDER_CHANGE_" + status, "ConfigProposal", id.toString(), Map.of("status", "PROPOSED"),
                Map.of("status", status), reason.strip());
        return repo.proposalForUpdate(id).orElseThrow();
    }

    // ---- violations (XB-001, XB-002)

    /** Queues a violation recorded here against a vehicle registered in the partner state (XB-001). */
    @Transactional
    public Message sendViolation(String partner, String localReference, String localCode, String vehiclePlate,
                                 String ownerFile, String currency, long amountMinor, Instant occurredAt,
                                 String location, String actor) {
        requirePartner(partner);
        if (localReference == null || localReference.isBlank() || localCode == null || vehiclePlate == null
                || currency == null || !currency.matches("^[A-Z]{3}$") || amountMinor < 0 || occurredAt == null) {
            throw invalid("OUTBOUND_VIOLATION_INVALID", "outbound violation fields are incomplete", "XB-001");
        }
        Optional<Message> existing = repo.violationByLocalReference(localReference)
                .filter(m -> "OUTBOUND".equals(m.direction()));
        if (existing.isPresent()) {
            return existing.get();
        }
        String external = repo.externalCode(partner, "VIOLATION", localCode).orElseThrow(() -> invalid(
                "CODE_MAPPING_MISSING", "violation code has no mapping for partner", "XB-007"));
        Map<String, Object> payload = location == null ? null : Map.of("location", location);
        Message m = new Message(UUID.randomUUID(), partner, UUID.randomUUID().toString(), "VIOLATION", "OUTBOUND",
                "PENDING", localReference, localCode, external, vehiclePlate, ownerFile, currency, amountMinor,
                occurredAt, clock.instant(), null, null, payload, null, null, null, null);
        queue(m, actor, "XB-001");
        return m;
    }

    /** A violation committed in the partner state by a vehicle registered here (XB-002); repeats return the first. */
    @Transactional
    public Message receiveViolation(String partner, String externalId, String externalCode, String plate,
                                    String location, String currency, long amountMinor, Instant occurredAt,
                                    String actor) {
        requirePartner(partner);
        Optional<Message> existing = repo.message(partner, externalId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (externalId == null || externalId.isBlank() || plate == null || plate.isBlank() || currency == null
                || !currency.matches("^[A-Z]{3}$") || amountMinor < 0 || occurredAt == null) {
            throw invalid("INBOUND_VIOLATION_INVALID", "received violation fields are incomplete", "XB-002");
        }
        String local = repo.localCode(partner, "VIOLATION", externalCode).orElseThrow(() -> invalid(
                "CODE_MAPPING_MISSING", "received violation code has no local mapping", "XB-007"));
        Map<String, Object> payload = location == null || location.isBlank() ? null : Map.of("location", location);
        Message m = new Message(UUID.randomUUID(), partner, externalId, "VIOLATION", "INBOUND", "RECEIVED", null,
                externalCode, local, plate, null, currency, amountMinor, occurredAt, clock.instant(), null, null,
                payload, null, null, null, null);
        repo.insert(m, actorOf(actor));
        audit(actor, "CROSSBORDER_VIOLATION_RECEIVED", m.id(), null, Map.of("partner", partner, "externalId",
                externalId, "status", "RECEIVED"), null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", m.id().toString());
        data.put("partner", partner);
        data.put("externalId", externalId);
        data.put("localCode", local);
        data.put("plate", plate);
        data.put("location", location == null ? "" : location);
        data.put("currency", currency);
        data.put("amountMinor", amountMinor);
        data.put("occurredAt", occurredAt.toString());
        event(actor, "itms.crossborder.violation-received", m.id(), data);
        return m;
    }

    /** From {@code itms.violation.crossborder-loaded}: the local violation exists (XB-002). */
    @Transactional
    public boolean violationLoaded(String partner, String externalId, String violationNumber) {
        Message m = repo.message(partner, externalId).orElse(null);
        if (m == null || !"RECEIVED".equals(m.status())) {
            return false;
        }
        Instant now = clock.instant();
        repo.setLocalReference(m.id(), violationNumber, now);
        repo.mark(m.id(), m.status(), "LOADED", now, "loaded as " + violationNumber, SYSTEM);
        audit(null, "CROSSBORDER_VIOLATION_LOADED", m.id(), Map.of("status", "RECEIVED"),
                Map.of("status", "LOADED", "localReference", violationNumber), null);
        return true;
    }

    /** From {@code itms.violation.crossborder-rejected}: the violation could not be loaded here (XB-002). */
    @Transactional
    public boolean violationRejected(String partner, String externalId, String reasonCode) {
        Message m = repo.message(partner, externalId).orElse(null);
        if (m == null || !"RECEIVED".equals(m.status())) {
            return false;
        }
        repo.mark(m.id(), m.status(), "REJECTED", clock.instant(), "not loaded: " + reasonCode, SYSTEM);
        audit(null, "CROSSBORDER_VIOLATION_NOT_LOADED", m.id(), Map.of("status", "RECEIVED"),
                Map.of("status", "REJECTED", "reasonCode", reasonCode), null);
        return true;
    }

    // ---- payments (XB-003, UAT-5)

    /**
     * From {@code itms.violation.paid}: a cross-border violation was paid here. The violation closes and a payment
     * notice is queued for the partner state; real-time partners receive it on the next delivery run (UAT-5).
     */
    @Transactional
    public Optional<Message> paidHere(String violationNumber) {
        Message v = repo.violationByLocalReference(violationNumber).orElse(null);
        if (v == null || v.paidAt() != null || !Set.of("LOADED", "PENDING", "SENT").contains(v.status())) {
            return Optional.empty();
        }
        Partner partner = repo.partner(v.partner()).orElseThrow();
        Instant now = clock.instant();
        repo.markPaid(v.id(), v.status(), "HERE", violationNumber, partner.commissionBasisPoints(), now, SYSTEM);
        audit(null, "CROSSBORDER_PAID_HERE", v.id(), Map.of("status", v.status()), Map.of("status", "PAID",
                "paidIn", "HERE", "commissionBasisPoints", partner.commissionBasisPoints()), null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("violationExternalId", v.externalId());
        payload.put("paidAt", now.toString());
        payload.put("paymentReference", violationNumber);
        Message notice = new Message(UUID.randomUUID(), v.partner(), UUID.randomUUID().toString(), "PAYMENT",
                "OUTBOUND", "PENDING", violationNumber, null, null, v.vehiclePlate(), null, v.currency(),
                v.amountMinor(), now, now, null, v.id(), payload, null, null, null, null);
        queue(notice, null, "XB-003");
        return Optional.of(notice);
    }

    /** The partner state reports a payment there; the local violation closes (XB-003). Repeats are ignored. */
    @Transactional
    public Message paymentNotice(String partner, String externalId, String paymentReference, long amountMinor,
                                 String currency, String actor) {
        requirePartner(partner);
        Message m = repo.message(partner, externalId).orElseThrow(() -> missing("EXCHANGE_MESSAGE_NOT_FOUND",
                "partner violation was not found", "XB-003"));
        if (m.paidAt() != null) {
            return m;
        }
        if (!"VIOLATION".equals(m.type()) || amountMinor != m.amountMinor() || !m.currency().equals(currency)
                || paymentReference == null || paymentReference.isBlank() || paymentReference.length() > 120
                || !Set.of("RECEIVED", "LOADED", "PENDING", "SENT", "OBJECTED").contains(m.status())) {
            throw invalid("PAYMENT_NOTICE_INVALID", "payment notice does not match an open violation", "XB-003");
        }
        Partner p = repo.partner(partner).orElseThrow();
        repo.markPaid(m.id(), m.status(), "PARTNER", paymentReference, p.commissionBasisPoints(), clock.instant(),
                actorOf(actor));
        audit(actor, "CROSSBORDER_PAYMENT_APPLIED", m.id(), Map.of("status", m.status()), Map.of("status", "PAID",
                "paidIn", "PARTNER", "paymentReference", paymentReference), null);
        event(actor, "itms.crossborder.payment-applied", m.id(), Map.of("messageId", m.id().toString(), "partner",
                partner, "externalId", externalId, "localReference", nz(m.localReference()), "paymentReference",
                paymentReference, "amountMinor", amountMinor, "currency", currency));
        return repo.message(m.id()).orElseThrow();
    }

    // ---- objections (XB-005)

    /**
     * Sends an objection to a partner violation to the issuing state. {@code pausePayment}: an officer submitted it
     * here, so violation-service is told to pause payment; a customer's online objection (VF-050) already did.
     */
    @Transactional
    public Message forwardObjection(String partner, String externalId, String violationNumber, String reason,
                                    boolean pausePayment, String actor) {
        Message v = (externalId != null ? repo.message(partner, externalId)
                : repo.violationByLocalReference(violationNumber)).orElseThrow(() -> missing(
                "EXCHANGE_MESSAGE_NOT_FOUND", "partner violation was not found", "XB-005"));
        if (!"INBOUND".equals(v.direction()) || !"VIOLATION".equals(v.type()) || !v.partner().equals(partner)) {
            throw invalid("OBJECTION_TARGET_INVALID", "only violations received from this partner can be objected to",
                    "XB-005");
        }
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw invalid("OBJECTION_INVALID", "objection reason is required", "XB-005");
        }
        if ("OBJECTED".equals(v.status())) {
            return v;
        }
        if (!Set.of("RECEIVED", "LOADED").contains(v.status())) {
            throw conflict("VIOLATION_NOT_OPEN", "only an open violation can be objected to", "XB-005");
        }
        Instant now = clock.instant();
        repo.mark(v.id(), v.status(), "OBJECTED", now, reason, actorOf(actor));
        audit(actor, "CROSSBORDER_OBJECTION_SUBMITTED", v.id(), Map.of("status", v.status()),
                Map.of("status", "OBJECTED"), reason);
        Message objection = new Message(UUID.randomUUID(), partner, UUID.randomUUID().toString(), "OBJECTION",
                "OUTBOUND", "PENDING", v.localReference(), null, null, v.vehiclePlate(), null, null, 0, now, now,
                null, v.id(), Map.of("violationExternalId", v.externalId(), "reason", reason), null, null, null, null);
        queue(objection, actor, "XB-005");
        if (pausePayment) {
            event(actor, "itms.crossborder.objection-submitted", v.id(), Map.of("messageId", v.id().toString(),
                    "partner", partner, "externalId", v.externalId(), "localReference", nz(v.localReference()),
                    "reason", reason));
        }
        return repo.message(v.id()).orElseThrow();
    }

    /** The issuing state's decision on an objection sent to it, applied automatically (XB-005). */
    @Transactional
    public Message partnerDecision(String partner, String externalId, String decision, String reason, String actor) {
        requirePartner(partner);
        Message v = repo.message(partner, externalId).orElseThrow(() -> missing("EXCHANGE_MESSAGE_NOT_FOUND",
                "partner violation was not found", "XB-005"));
        if (!("ACCEPTED".equals(decision) || "REJECTED".equals(decision)) || (reason != null
                && reason.length() > 500)) {
            throw invalid("DECISION_INVALID", "decision must be ACCEPTED or REJECTED", "XB-005");
        }
        if (!"INBOUND".equals(v.direction()) || !"OBJECTED".equals(v.status())) {
            throw conflict("NO_OBJECTION_PENDING", "no objection to this violation awaits the partner's decision",
                    "XB-005");
        }
        String next = "ACCEPTED".equals(decision) ? "CANCELLED" : v.localReference() == null ? "RECEIVED" : "LOADED";
        repo.mark(v.id(), v.status(), next, clock.instant(), "partner decision " + decision
                + (reason == null ? "" : ": " + reason), actorOf(actor));
        audit(actor, "CROSSBORDER_OBJECTION_DECIDED", v.id(), Map.of("status", "OBJECTED"),
                Map.of("status", next, "decision", decision), reason);
        event(actor, "itms.crossborder.objection-decided", v.id(), Map.of("messageId", v.id().toString(), "partner",
                partner, "externalId", externalId, "localReference", nz(v.localReference()), "decision", decision,
                "reason", nz(reason)));
        return repo.message(v.id()).orElseThrow();
    }

    /** A partner forwards its customer's objection to a violation sent from here; it joins the local queue. */
    @Transactional
    public Message receiveObjection(String partner, String externalId, String reason, String actor) {
        requirePartner(partner);
        Message v = repo.message(partner, externalId).orElseThrow(() -> missing("EXCHANGE_MESSAGE_NOT_FOUND",
                "violation sent to this partner was not found", "XB-005"));
        if (!"OUTBOUND".equals(v.direction()) || !"VIOLATION".equals(v.type())) {
            throw invalid("OBJECTION_TARGET_INVALID", "the objection must name a violation sent from here", "XB-005");
        }
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw invalid("OBJECTION_INVALID", "objection reason is required", "XB-005");
        }
        if ("OBJECTED".equals(v.status())) {
            return v;
        }
        if (!Set.of("PENDING", "SENT").contains(v.status())) {
            throw conflict("VIOLATION_NOT_OPEN", "the violation is no longer open", "XB-005");
        }
        repo.mark(v.id(), v.status(), "OBJECTED", clock.instant(), reason, actorOf(actor));
        audit(actor, "CROSSBORDER_OBJECTION_RECEIVED", v.id(), Map.of("status", v.status()),
                Map.of("status", "OBJECTED"), reason);
        event(actor, "itms.crossborder.objection-received", v.id(), Map.of("messageId", v.id().toString(),
                "partner", partner, "externalId", externalId, "localReference", nz(v.localReference()),
                "reason", reason));
        return repo.message(v.id()).orElseThrow();
    }

    /** From {@code itms.violation.objection-decided}: the local decision is sent back to the partner (XB-005). */
    @Transactional
    public Optional<Message> localObjectionDecided(String violationNumber, String decision, String reason) {
        Message v = repo.violationByLocalReference(violationNumber).orElse(null);
        if (v == null || !"OUTBOUND".equals(v.direction()) || !"OBJECTED".equals(v.status())
                || !("ACCEPT".equals(decision) || "REJECT".equals(decision))) {
            return Optional.empty();
        }
        String outcome = "ACCEPT".equals(decision) ? "ACCEPTED" : "REJECTED";
        Instant now = clock.instant();
        repo.mark(v.id(), "OBJECTED", "ACCEPTED".equals(outcome) ? "CANCELLED" : "SENT", now,
                "local decision " + outcome, SYSTEM);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("violationExternalId", v.externalId());
        payload.put("decision", outcome);
        payload.put("reason", nz(reason));
        Message notice = new Message(UUID.randomUUID(), v.partner(), UUID.randomUUID().toString(),
                "OBJECTION_DECISION", "OUTBOUND", "PENDING", violationNumber, null, null, v.vehiclePlate(), null, null,
                0, now, now, null, v.id(), payload, null, null, null, null);
        queue(notice, null, "XB-005");
        return Optional.of(notice);
    }

    // ---- accident, permit and licence data (XB-006, DL-064)

    @Transactional
    public Message sendData(String partner, String type, String reference, Map<String, Object> payload, String actor) {
        Partner p = requirePartner(partner);
        requireData(p, type, reference, payload);
        Instant now = clock.instant();
        Message m = new Message(UUID.randomUUID(), partner, UUID.randomUUID().toString(), type, "OUTBOUND",
                "PENDING", reference, null, null, null, null, null, 0, now, now, null, null, payload, null, null,
                null, null);
        queue(m, actor, "XB-006");
        return m;
    }

    @Transactional
    public Message receiveData(String partner, String type, String externalId, String reference,
                               Map<String, Object> payload, String actor) {
        Partner p = requirePartner(partner);
        Optional<Message> existing = repo.message(partner, externalId);
        if (existing.isPresent()) {
            return existing.get();
        }
        requireData(p, type, reference, payload);
        if (externalId == null || externalId.isBlank() || externalId.length() > 120) {
            throw invalid("EXTERNAL_ID_REQUIRED", "the partner's message id is required", "XB-006");
        }
        Instant now = clock.instant();
        Message m = new Message(UUID.randomUUID(), partner, externalId, type, "INBOUND", "RECEIVED", reference,
                null, null, null, null, null, 0, now, now, null, null, payload, null, null, null, null);
        repo.insert(m, actorOf(actor));
        audit(actor, "CROSSBORDER_DATA_RECEIVED", m.id(), null, Map.of("partner", partner, "type", type,
                "reference", reference), null);
        event(actor, "itms.crossborder.data-received", m.id(), Map.of("messageId", m.id().toString(), "partner",
                partner, "type", type, "reference", reference));
        return m;
    }

    private void requireData(Partner p, String type, String reference, Map<String, Object> payload) {
        if (!DATA_TYPES.contains(type) || reference == null || reference.isBlank() || reference.length() > 120
                || payload == null || payload.isEmpty()) {
            throw invalid("DATA_EXCHANGE_INVALID", "type, reference and content are required", "XB-006", "DL-064");
        }
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            Object v = e.getValue();
            if (!e.getKey().matches("^[A-Za-z][A-Za-z0-9_]{0,63}$") || !(v instanceof String || v instanceof Number
                    || v instanceof Boolean) || (v instanceof String text && text.length() > 2000)) {
                throw invalid("DATA_EXCHANGE_INVALID", "content holds flat text, number or true/false fields",
                        "XB-006", "DL-064");
            }
        }
        if (!p.allows(type)) {
            throw conflict("AGREEMENT_EXCLUDES_DATA", "the agreement with this partner does not cover " + type,
                    "XB-006", "DL-064");
        }
    }

    // ---- delivery (XB-001, XB-008)

    @Transactional(readOnly = true)
    public List<Partner> partners(String mode) {
        return repo.partners(mode);
    }

    @Transactional(readOnly = true)
    public List<Message> due(String partner, Instant from, Instant to, int limit) {
        return repo.due(partner, from, to, clock.instant(), limit);
    }

    /** Claims one delivery attempt; false if another instance has it or it is not due. */
    @Transactional
    public boolean claim(UUID messageId) {
        return repo.claimDelivery(messageId, clock.instant(), settings.retryBase().toSeconds(),
                settings.retryMax().toSeconds(), settings.maxAttempts()) == 1;
    }

    /** What the partner receives: this state's code, the message and its references (ICD envelope). */
    public Map<String, Object> envelope(Message m) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("messageId", m.externalId());
        e.put("sender", settings.localState());
        e.put("type", m.type());
        e.put("reference", m.localReference());
        e.put("code", m.mappedCode());
        e.put("plate", m.vehiclePlate());
        e.put("currency", m.currency());
        e.put("amountMinor", m.amountMinor());
        e.put("occurredAt", m.occurredAt().toString());
        e.put("content", m.payload());
        return e;
    }

    /** Applies the partner's answer to one attempt (synchronous HTTP reply or a later acknowledgement). */
    @Transactional
    public Message delivered(UUID id, Ports.PartnerTransport.Outcome outcome, String actor) {
        Message message = repo.message(id).orElseThrow(() -> missing("EXCHANGE_MESSAGE_NOT_FOUND",
                "exchange message does not exist", "XB-001", "XB-008"));
        if (!"OUTBOUND".equals(message.direction())) {
            throw invalid("EXCHANGE_ACK_DIRECTION_INVALID", "only outbound messages accept delivery acknowledgements",
                    "XB-001", "XB-008");
        }
        String response = outcome.response() == null ? null : outcome.response().length() > 500
                ? outcome.response().substring(0, 500) : outcome.response();
        String delivery = repo.acknowledgeDelivery(id, outcome.accepted(), outcome.permanent(), response,
                outcome.partnerReference(), clock.instant(), settings.maxAttempts());
        if (delivery == null) {
            return message;
        }
        if (!"PENDING".equals(delivery) && "PENDING".equals(message.status())) {
            String status = "ACKNOWLEDGED".equals(delivery) ? "SENT" : "REJECTED";
            repo.mark(id, "PENDING", status, clock.instant(), response, actorOf(actor));
        }
        audit(actor, "CROSSBORDER_DELIVERY_" + delivery, id, null, Map.of("delivery", delivery, "accepted",
                outcome.accepted()), response);
        event(actor, "itms.crossborder.delivery-" + delivery.toLowerCase(java.util.Locale.ROOT), id, Map.of(
                "messageId", id.toString(), "partner", message.partner(), "type", message.type(), "delivery",
                delivery));
        return repo.message(id).orElseThrow();
    }

    // ---- settlement (XB-004)

    @Transactional(readOnly = true)
    public List<Settlement> settlement(String partner, Instant from, Instant to) {
        requireWindow(from, to);
        repo.partner(partner).orElseThrow(() -> missing("PARTNER_NOT_FOUND", "partner state is not configured",
                "XB-004"));
        List<Settlement> out = new ArrayList<>();
        for (Map.Entry<String, List<Ports.StatementLine>> e : lines(repo.unsettled(partner, from, to)).entrySet()) {
            Ports.Statement s = totals(UUID.randomUUID(), partner, from, to, e.getKey(), e.getValue(), null, null);
            out.add(new Settlement(partner, from, to, s.currency(), s.collectedHereMinor(), s.commissionHereMinor(),
                    s.owedToPartnerMinor(), s.collectedByPartnerMinor(), s.commissionPartnerMinor(),
                    s.owedByPartnerMinor(), s.netMinor(), s.lineCount()));
        }
        return out;
    }

    /** Issues one statement per currency for the payments not yet settled in the window (XB-004). */
    @Transactional
    public List<StatementView> issueStatements(String partner, Instant from, Instant to, String actor) {
        requireWindow(from, to);
        if (to.isAfter(clock.instant())) {
            throw invalid("SETTLEMENT_WINDOW_INVALID", "a statement covers a period that has ended", "XB-004");
        }
        repo.partner(partner).orElseThrow(() -> missing("PARTNER_NOT_FOUND", "partner state is not configured",
                "XB-004"));
        List<StatementView> out = new ArrayList<>();
        Instant now = clock.instant();
        for (Map.Entry<String, List<Ports.StatementLine>> e : lines(repo.unsettled(partner, from, to)).entrySet()) {
            Ports.Statement s = totals(UUID.randomUUID(), partner, from, to, e.getKey(), e.getValue(),
                    actorOf(actor), now);
            repo.insertStatement(s, e.getValue());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("statementId", s.id().toString());
            data.put("partner", partner);
            data.put("from", from.toString());
            data.put("to", to.toString());
            data.put("currency", s.currency());
            data.put("owedToPartnerMinor", s.owedToPartnerMinor());
            data.put("owedByPartnerMinor", s.owedByPartnerMinor());
            data.put("netMinor", s.netMinor());
            data.put("lineCount", s.lineCount());
            audit(actor, "CROSSBORDER_SETTLEMENT_ISSUED", s.id(), null, data, null);
            event(actor, "itms.crossborder.settlement-issued", s.id(), data);
            out.add(new StatementView(s, e.getValue()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public StatementView statement(UUID id) {
        Ports.Statement s = repo.statement(id).orElseThrow(() -> missing("STATEMENT_NOT_FOUND",
                "settlement statement does not exist", "XB-004"));
        return new StatementView(s, repo.statementLines(id));
    }

    @Transactional(readOnly = true)
    public List<Ports.Statement> statements(String partner, int limit) {
        return repo.statements(partner, page(limit));
    }

    /** Lines per currency; the commission rate is the one in force when each payment was recorded. */
    private static Map<String, List<Ports.StatementLine>> lines(List<Message> paid) {
        Map<String, List<Ports.StatementLine>> byCurrency = new TreeMap<>();
        for (Message m : paid) {
            int bps = m.commissionBasisPoints();
            long commission = Math.multiplyExact(m.amountMinor(), (long) bps) / 10000;
            byCurrency.computeIfAbsent(m.currency(), c -> new ArrayList<>())
                    .add(new Ports.StatementLine(m.id(), m.paidIn(), m.amountMinor(), bps, commission));
        }
        return byCurrency;
    }

    private static Ports.Statement totals(UUID id, String partner, Instant from, Instant to, String currency,
                                          List<Ports.StatementLine> lines, String actor, Instant at) {
        long here = 0;
        long hereCommission = 0;
        long there = 0;
        long thereCommission = 0;
        for (Ports.StatementLine l : lines) {
            if ("HERE".equals(l.paidIn())) {
                here += l.amountMinor();
                hereCommission += l.commissionMinor();
            } else {
                there += l.amountMinor();
                thereCommission += l.commissionMinor();
            }
        }
        long owedTo = here - hereCommission;
        long owedBy = there - thereCommission;
        return new Ports.Statement(id, partner, from, to, currency, here, hereCommission, owedTo, there,
                thereCommission, owedBy, owedBy - owedTo, lines.size(), actor, at);
    }

    // ---- reading (XB-007)

    @Transactional(readOnly = true)
    public Message message(UUID id) {
        return repo.message(id).orElseThrow(() -> missing("EXCHANGE_MESSAGE_NOT_FOUND",
                "exchange message does not exist", "XB-007"));
    }

    @Transactional(readOnly = true)
    public List<Message> messages(String partner, String type, String status, int limit) {
        return repo.messages(partner, type, status, page(limit));
    }

    @Transactional(readOnly = true)
    public List<Ports.History> history(UUID id) {
        message(id);
        return repo.history(id);
    }

    @Transactional(readOnly = true)
    public Optional<Partner> partner(String code) {
        return repo.partner(code);
    }

    // ---- helpers

    private void queue(Message m, String actor, String requirement) {
        repo.insert(m, actorOf(actor));
        repo.createDelivery(m.id(), clock.instant());
        audit(actor, "CROSSBORDER_" + m.type() + "_QUEUED", m.id(), null, Map.of("partner", m.partner(),
                "localReference", nz(m.localReference()), "status", "PENDING", "requirement", requirement), null);
        event(actor, "itms.crossborder.message-queued", m.id(), Map.of("messageId", m.id().toString(), "partner",
                m.partner(), "type", m.type()));
    }

    private Partner requirePartner(String code) {
        Partner p = repo.partner(code).orElseThrow(() -> missing("PARTNER_NOT_FOUND",
                "partner state is not configured", "XB-001"));
        if (!"ACTIVE".equals(p.status())) {
            throw conflict("PARTNER_INACTIVE", "partner state is suspended", "XB-001");
        }
        return p;
    }

    private static void requireWindow(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw invalid("SETTLEMENT_WINDOW_INVALID", "settlement window is invalid", "XB-004");
        }
    }

    private void audit(String actor, String action, UUID id, Object before, Object after, String reason) {
        if (actor == null) {
            audit.recordAs(CurrentActor.system(SYSTEM), action, "ExchangeMessage", id.toString(), before, after,
                    reason);
        } else {
            audit.record(action, "ExchangeMessage", id.toString(), before, after, reason);
        }
    }

    private void event(String actor, String type, UUID subject, Map<String, Object> data) {
        if (actor == null) {
            events.recordAs(CurrentActor.system(SYSTEM), type, subject.toString(), data);
        } else {
            events.record(type, subject.toString(), data);
        }
    }

    private static String actorOf(String actor) {
        return actor == null ? SYSTEM : actor;
    }

    private static int page(int limit) {
        if (limit < 1 || limit > 500) {
            throw invalid("PAGE_INVALID", "limit must be 1..500", "XB-007");
        }
        return limit;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static boolean bool(Map<String, Object> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }

    static DomainException invalid(String code, String message, String... ids) {
        return new DomainException(DomainException.Kind.VALIDATION, code, message, List.of(ids));
    }

    static DomainException missing(String code, String message, String... ids) {
        return new DomainException(DomainException.Kind.NOT_FOUND, code, message, List.of(ids));
    }

    static DomainException conflict(String code, String message, String... ids) {
        return new DomainException(DomainException.Kind.CONFLICT, code, message, List.of(ids));
    }
}

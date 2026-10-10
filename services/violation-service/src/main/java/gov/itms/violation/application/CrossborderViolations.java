package gov.itms.violation.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.violation.application.Ports.CrossborderLinks.Direction;
import gov.itms.violation.application.Ports.CrossborderLinks.Link;
import gov.itms.violation.domain.CatalogueEntry;
import gov.itms.violation.domain.DomainException;
import gov.itms.violation.domain.Violation;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Violations that cross a border (Increment 9):
 * <ul>
 *   <li>a violation recorded here against a vehicle registered in a partner state is linked to that state and
 *       announced to crossborder-service, which sends it (XB-001);</li>
 *   <li>a violation received from a partner state is loaded against the local vehicle and owner, notified through the
 *       usual {@code itms.violation.recorded} event and made payable through every local channel (XB-002);</li>
 *   <li>a payment made in the partner state closes the local violation and withdraws its payable (XB-003);</li>
 *   <li>a customer objection to a partner violation pauses payment until the partner's decision arrives, and that
 *       decision is applied automatically (XB-005); an objection the partner forwards against a violation recorded
 *       here enters the local objection queue.</li>
 * </ul>
 * Partner violations carry the partner's fine and add no black points (Q-079 interim).
 */
public class CrossborderViolations {

    static final String SYSTEM = "violation-crossborder-consumer";
    private static final String PARTNER = "^[A-Z0-9]{2,12}$";

    /** Settings: the cancellation reason applied when a partner accepts an objection, and the payable beneficiary. */
    public record Settings(String acceptedObjectionReason, String beneficiary) {
        public Settings {
            if (acceptedObjectionReason == null || acceptedObjectionReason.isBlank() || beneficiary == null
                    || beneficiary.isBlank()) {
                throw new IllegalArgumentException("itms.violations.crossborder settings are required");
            }
        }
    }

    /** What a partner state sends, with its code already mapped to the local catalogue (XB-007). */
    public record PartnerViolation(String partner, String externalId, String catalogueCode, Instant occurredAt,
                                   String location, String plate, String currency, long amountMinor) {
    }

    /** The outcome of loading: the local number, or why the violation could not be loaded. */
    public record Loaded(String violationNumber, String rejectionCode) {
        public boolean loaded() {
            return violationNumber != null;
        }
    }

    private final ViolationService violations;
    private final Ports.ViolationRepository repo;
    private final Ports.CrossborderLinks links;
    private final Ports.Vehicles vehicles;
    private final Ports.Payables payables;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Settings settings;
    private final Clock clock;

    public CrossborderViolations(ViolationService violations, Ports.ViolationRepository repo,
                                 Ports.CrossborderLinks links, Ports.Vehicles vehicles, Ports.Payables payables,
                                 AuditRecorder audit, DomainEventRecorder events, Settings settings, Clock clock) {
        this.violations = violations;
        this.repo = repo;
        this.links = links;
        this.vehicles = vehicles;
        this.payables = payables;
        this.audit = audit;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    /** Records a violation by a vehicle registered in a partner state and announces it for sending (XB-001). */
    @Transactional
    public Violation recordForeign(Violation.Report report, String registrationState, Caller caller) {
        if (registrationState == null || !registrationState.matches(PARTNER)) {
            throw DomainException.validation("REGISTRATION_STATE_INVALID",
                    "give the partner state that registered the vehicle", List.of("XB-001"));
        }
        if (report.plate() == null) {
            throw DomainException.validation("PLATE_REQUIRED", "a partner-state vehicle is identified by its plate",
                    List.of("XB-001"));
        }
        Violation v = violations.record(report, caller);
        Instant now = clock.instant();
        links.insert(new Link(v.id(), v.number(), registrationState, Direction.OUTBOUND, null), now);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("violationNumber", v.number());
        data.put("partner", registrationState);
        data.put("catalogueCode", v.catalogueCode());
        data.put("occurredAt", report.occurredAt().toString());
        data.put("location", report.location());
        data.put("plate", report.plate());
        data.put("ownerFileNumber", report.responsibleFileNumber());
        data.put("currency", violations.currency());
        data.put("amountMinor", v.fineMinor());
        audit.record("VIOLATION_CROSSBORDER_OUTBOUND", ViolationService.ENTITY, v.number(), null, data, null);
        events.record("itms.violation.crossborder-recorded", v.number(), data);
        return v;
    }

    /**
     * From {@code itms.crossborder.violation-received}: loads the partner violation against the local vehicle and
     * owner (XB-002). A repeated message returns the first result; a violation that cannot be loaded is reported back
     * with {@code itms.violation.crossborder-rejected} so the exchange log shows why.
     */
    @Transactional
    public Loaded loadFromPartner(PartnerViolation p) {
        Optional<Link> existing = links.byExternalId(p.partner(), p.externalId());
        if (existing.isPresent()) {
            return new Loaded(existing.get().violationNumber(), null);
        }
        CurrentActor system = CurrentActor.system(SYSTEM);
        String rejection = check(p);
        Ports.Vehicles.Registered vehicle = null;
        if (rejection == null) {
            vehicle = vehicles.byPlate(p.plate()).orElse(null);
            if (vehicle == null || vehicle.ownerFileNumber() == null) {
                rejection = "VEHICLE_NOT_REGISTERED_HERE";
            }
        }
        CatalogueEntry entry = null;
        Violation.Report report = null;
        if (rejection == null) {
            LocalDate day = violations.dayOf(p.occurredAt());
            try {
                entry = violations.inForce(p.catalogueCode(), vehicle.region(), day);
                String location = p.location() == null || p.location().isBlank()
                        ? "Partner state " + p.partner() : p.location();
                report = new Violation.Report(entry.code(), p.occurredAt(), vehicle.region(),
                        location.length() > 300 ? location.substring(0, 300) : location, p.plate(), vehicle.vin(),
                        vehicle.ownerFileNumber(), null, null, null, Violation.Source.PARTNER, null, List.of());
            } catch (DomainException | IllegalArgumentException e) {
                rejection = e instanceof DomainException d ? d.code() : "VIOLATION_FIELDS_INVALID";
            }
        }
        if (rejection != null) {
            Map<String, Object> data = Map.of("partner", p.partner(), "externalId", p.externalId(),
                    "reasonCode", rejection);
            audit.recordAs(system, "VIOLATION_CROSSBORDER_REJECTED", "ExchangeMessage",
                    p.partner() + ":" + p.externalId(), null, data, null);
            events.recordAs(system, "itms.violation.crossborder-rejected", p.partner() + ":" + p.externalId(), data);
            return new Loaded(null, rejection);
        }
        Instant now = clock.instant();
        String number = violations.nextNumber(violations.dayOf(p.occurredAt()));
        Violation v = new Violation(UUID.randomUUID(), number, report, entry.code(), entry.versionNo(),
                p.amountMinor(), 0, false, SYSTEM, now, Violation.Status.OPEN, null, null, null);
        repo.insertViolation(v);
        links.insert(new Link(v.id(), number, p.partner(), Direction.INBOUND, p.externalId()), now);
        if (v.fineMinor() > 0) {
            v.payableRegistered(payables.register(number, report.responsibleFileNumber(), report.region(),
                    p.currency(), v.fineMinor(), entry.financialItem(), settings.beneficiary(),
                    "Partner state " + p.partner() + " violation " + p.externalId()));
            repo.updateViolation(v);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>(ViolationService.snapshot(v, entry));
        snapshot.put("crossBorderPartner", p.partner());
        snapshot.put("externalId", p.externalId());
        snapshot.put("currency", p.currency());
        audit.recordAs(system, "VIOLATION_LOADED_FROM_PARTNER", ViolationService.ENTITY, number, null, snapshot,
                null);
        events.recordAs(system, "itms.violation.recorded", number, snapshot);
        events.recordAs(system, "itms.violation.crossborder-loaded", number, Map.of("violationNumber", number,
                "partner", p.partner(), "externalId", p.externalId()));
        return new Loaded(number, null);
    }

    private String check(PartnerViolation p) {
        if (p.partner() == null || !p.partner().matches(PARTNER) || p.externalId() == null
                || p.externalId().isBlank() || p.catalogueCode() == null || p.occurredAt() == null
                || p.plate() == null || p.plate().isBlank() || p.amountMinor() < 0) {
            return "VIOLATION_FIELDS_INVALID";
        }
        if (p.occurredAt().isAfter(clock.instant().plusSeconds(60))) {
            return "OCCURRED_AT_INVALID";
        }
        // Q-079 (b): no exchange-rate source is defined, so only fines in the local currency are loaded.
        if (!violations.currency().equals(p.currency())) {
            return "CURRENCY_NOT_SUPPORTED";
        }
        return null;
    }

    /** From {@code itms.crossborder.payment-applied}: the violation was paid in the partner state (XB-003). */
    @Transactional
    public boolean paidInPartnerState(String number, String partner, String paymentReference) {
        Link link = links.byNumber(number).orElse(null);
        Violation v = repo.findForUpdate(number).orElse(null);
        if (link == null || v == null || !link.partner().equals(partner) || v.status() != Violation.Status.OPEN) {
            return false;
        }
        v.paid(clock.instant());
        if (v.payableId() != null) {
            payables.cancel(v.payableId(), "paid in partner state " + partner + ": " + paymentReference);
        }
        repo.updateViolation(v);
        CurrentActor system = CurrentActor.system(SYSTEM);
        Map<String, Object> data = Map.of("violationNumber", number, "partner", partner, "paymentReference",
                paymentReference, "recipientId", v.report().responsibleFileNumber());
        audit.recordAs(system, "VIOLATION_PAID_IN_PARTNER_STATE", ViolationService.ENTITY, number,
                Map.of("status", "OPEN"), Map.of("status", "PAID", "partner", partner), null);
        // A distinct event: itms.violation.paid would send a payment notice back to the partner that sent this one.
        events.recordAs(system, "itms.violation.paid-in-partner-state", number, data);
        return true;
    }

    /** From {@code itms.crossborder.objection-submitted}: payment pauses while the partner decides (XB-005). */
    @Transactional
    public boolean objectionForwarded(String number, String partner) {
        Link link = links.byNumber(number).orElse(null);
        Violation v = repo.findForUpdate(number).orElse(null);
        if (link == null || v == null || link.direction() != Direction.INBOUND || !link.partner().equals(partner)
                || v.status() != Violation.Status.OPEN) {
            return false;
        }
        if (v.payableId() != null) {
            payables.hold(v.payableId(), "OBJECTION:" + number, "objection forwarded to partner state " + partner);
        }
        audit.recordAs(CurrentActor.system(SYSTEM), "VIOLATION_CROSSBORDER_OBJECTION_FORWARDED",
                ViolationService.ENTITY, number, null, Map.of("partner", partner), null);
        return true;
    }

    /**
     * From {@code itms.crossborder.objection-decided}: the issuing state's decision is applied (XB-005). Accepted:
     * the violation is cancelled and its payable withdrawn; rejected: payment resumes.
     */
    @Transactional
    public boolean partnerDecided(String number, String partner, boolean accepted, String reason) {
        Link link = links.byNumber(number).orElse(null);
        Violation v = repo.findForUpdate(number).orElse(null);
        if (link == null || v == null || link.direction() != Direction.INBOUND || !link.partner().equals(partner)) {
            return false;
        }
        CurrentActor system = CurrentActor.system(SYSTEM);
        String note = reason == null || reason.isBlank() ? "decided by partner state " + partner : reason;
        if (accepted && v.status() == Violation.Status.OPEN) {
            v.cancel(settings.acceptedObjectionReason(), clock.instant());
            if (v.payableId() != null) {
                payables.cancel(v.payableId(), "objection accepted by partner state " + partner);
            }
            repo.updateViolation(v);
            repo.cancelPointsOf(v.id());
            audit.recordAs(system, "VIOLATION_CANCELLED", ViolationService.ENTITY, number, Map.of("status", "OPEN"),
                    Map.of("status", "CANCELLED", "reasonCode", settings.acceptedObjectionReason()), note);
            events.recordAs(system, "itms.violation.cancelled", number, Map.of("violationNumber", number,
                    "reasonCode", settings.acceptedObjectionReason(), "recipientId",
                    v.report().responsibleFileNumber()));
            return true;
        }
        if (!accepted && v.payableId() != null && v.status() == Violation.Status.OPEN) {
            payables.liftHold(v.payableId(), "OBJECTION:" + number, "objection rejected by partner state "
                    + partner);
            audit.recordAs(system, "VIOLATION_CROSSBORDER_OBJECTION_REJECTED", ViolationService.ENTITY, number,
                    null, Map.of("partner", partner), note);
            events.recordAs(system, "itms.violation.crossborder-objection-rejected", number, Map.of(
                    "violationNumber", number, "partner", partner, "recipientId",
                    v.report().responsibleFileNumber()));
            return true;
        }
        return false;
    }

    /** True if the violation was sent to, or received from, a partner state. */
    @Transactional(readOnly = true)
    public Optional<Link> link(String number) {
        return links.byNumber(number);
    }
}

package gov.itms.violation.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.violation.domain.DomainException;
import gov.itms.violation.domain.Objection;
import gov.itms.violation.domain.Violation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Customer objections and the supervisor review queue (VF-050..VF-052). */
public class ObjectionService {

    public static final String SUBMIT = "violation.objection.submit";
    public static final String REVIEW = "violation.objection.review";

    private static final int QUEUE_BATCH = 500;
    private static final int QUEUE_SCAN_LIMIT = 20_000;

    private final Ports.Objections objections;
    private final Ports.CrossborderLinks links;
    private final ViolationService violations;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;
    private final Duration objectionWindow;

    public ObjectionService(Ports.Objections objections, Ports.CrossborderLinks links, ViolationService violations,
                            AuditRecorder audit, DomainEventRecorder events, Clock clock, Duration objectionWindow) {
        this.objections = objections;
        this.links = links;
        this.violations = violations;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.objectionWindow = objectionWindow;
        if (objectionWindow.isNegative() || objectionWindow.isZero()
                || objectionWindow.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("itms.violations.objection-window must be 1 second to 365 days");
        }
    }

    @Transactional
    public Objection submit(String number, String reasonCode, String explanation, List<String> evidenceIds,
                            Caller caller) {
        if (!caller.grants().holds(SUBMIT)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "OBJECTION_SUBMIT_FORBIDDEN",
                    "cannot submit an objection", List.of("VF-050"));
        }
        Violation violation = violations.getForObjection(number, caller);
        Instant now = clock.instant();
        if (violations.inInstalmentPlan(violation)) {
            // Q-083 (e): the customer accepted the fine by choosing to pay it in instalments.
            throw DomainException.conflict("VIOLATION_IN_INSTALMENT_PLAN",
                    "a violation in an instalment plan cannot be challenged", List.of("VF-050", "VF-071"));
        }
        if (now.isAfter(violation.recordedAt().plus(objectionWindow))) {
            throw DomainException.validation("OBJECTION_WINDOW_CLOSED", "the objection period has ended",
                    List.of("VF-050"), violation.recordedAt().plus(objectionWindow).toString());
        }
        if (violation.status() != Violation.Status.OPEN) {
            throw DomainException.conflict("VIOLATION_NOT_OPEN", "only an open violation can be challenged",
                    List.of("VF-050"));
        }
        List<String> evidence = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        Objection objection = Objection.submitted(UUID.randomUUID(), violation.id(), number, caller.actor().id(), now,
                reasonCode, explanation, evidence);
        if (!objections.insert(objection)) {
            throw DomainException.conflict("OBJECTION_EXISTS", "an objection has already been submitted",
                    List.of("VF-050"));
        }
        violations.pauseForObjection(number, caller);
        audit.record("VIOLATION_OBJECTION_SUBMITTED", "Violation", number, null,
                Map.of("objectionId", objection.id().toString(), "reasonCode", reasonCode,
                        "evidenceCount", evidence.size()), null);
        events.record("itms.violation.objection-submitted", number,
                Map.of("violationNumber", number, "objectionId", objection.id().toString()));
        // XB-005: an objection to a partner-state violation is sent to the issuing state, which decides it.
        inboundPartner(number).ifPresent(partner -> events.record("itms.violation.crossborder-objection",
                number, Map.of("violationNumber", number, "partner", partner, "objectionId",
                        objection.id().toString(), "reason", explanation == null ? reasonCode : explanation)));
        return objection;
    }

    /**
     * An objection a partner state forwards against a violation recorded here (XB-005). It joins the review queue like
     * a local one; one that cannot be accepted (window closed, not open, already objected) is answered at once with
     * {@code itms.violation.objection-decided} REJECT so the partner receives a decision.
     */
    @Transactional
    public boolean submitFromPartner(String number, String partner, String explanation) {
        CurrentActor system = CurrentActor.system(CrossborderViolations.SYSTEM);
        Violation violation = violations.lockForPartnerObjection(number).orElse(null);
        if (violation == null) {
            return false;
        }
        Instant now = clock.instant();
        String refusal = null;
        if (now.isAfter(violation.recordedAt().plus(objectionWindow))) {
            refusal = "OBJECTION_WINDOW_CLOSED";
        } else if (violation.status() != Violation.Status.OPEN) {
            refusal = "VIOLATION_NOT_OPEN";
        }
        String text = explanation == null || explanation.isBlank() ? "objection from partner state " + partner
                : explanation.length() > 1000 ? explanation.substring(0, 1000) : explanation;
        if (refusal == null && !objections.insert(Objection.submitted(UUID.randomUUID(), violation.id(), number,
                "partner:" + partner, now, "PARTNER_OBJECTION", text, List.of()))) {
            refusal = "OBJECTION_EXISTS";
        }
        if (refusal != null) {
            audit.recordAs(system, "VIOLATION_PARTNER_OBJECTION_REFUSED", "Violation", number, null,
                    Map.of("partner", partner, "reasonCode", refusal), null);
            events.recordAs(system, "itms.violation.objection-decided", number, Map.of(
                    "violationNumber", number, "decision", "REJECT", "reason", refusal));
            return false;
        }
        audit.recordAs(system, "VIOLATION_OBJECTION_SUBMITTED", "Violation", number, null,
                Map.of("partner", partner, "reasonCode", "PARTNER_OBJECTION"), null);
        events.recordAs(system, "itms.violation.objection-submitted", number,
                Map.of("violationNumber", number, "partner", partner));
        return true;
    }

    /** Records the issuing partner state's decision on the pending objection to its violation (XB-005). */
    @Transactional
    public boolean decidedByPartner(String number, String partner, boolean accepted, String reason) {
        String text = reason == null || reason.isBlank() ? "decided by partner state " + partner
                : reason.length() > 500 ? reason.substring(0, 500) : reason;
        boolean decided = objections.decidePending(number, accepted ? Objection.Decision.ACCEPT
                : Objection.Decision.REJECT, "partner:" + partner, clock.instant(), text);
        if (decided) {
            audit.recordAs(CurrentActor.system(CrossborderViolations.SYSTEM), "VIOLATION_OBJECTION_DECIDED",
                    "Violation", number, Map.of("status", "PENDING"),
                    Map.of("status", accepted ? "ACCEPTED" : "REJECTED", "partner", partner), text);
        }
        return decided;
    }

    private Optional<String> inboundPartner(String number) {
        return links.byNumber(number).filter(l -> l.direction() == Ports.CrossborderLinks.Direction.INBOUND)
                .map(Ports.CrossborderLinks.Link::partner);
    }

    @Transactional(readOnly = true)
    public List<Objection> queue(int limit, Caller caller) {
        if (!caller.grants().holds(REVIEW)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "OBJECTION_REVIEW_FORBIDDEN",
                    "cannot view the objection queue", List.of("VF-051"));
        }
        if (limit < 1 || limit > 500) {
            throw DomainException.validation("PAGE_INVALID", "limit must be 1..500", List.of("VF-051"));
        }
        // Objections to partner-state violations are decided by the issuing state (XB-005), not in this queue.
        // CC-001: the reviewer sees only objections to violations in regions their review grant reaches.
        List<Objection> page = new ArrayList<>();
        Instant after = null;
        UUID afterId = null;
        for (int scanned = 0; page.size() < limit && scanned < QUEUE_SCAN_LIMIT; scanned += QUEUE_BATCH) {
            List<Ports.Objections.Queued> batch = objections.pendingLocal(QUEUE_BATCH, after, afterId);
            for (Ports.Objections.Queued q : batch) {
                if (page.size() < limit && caller.grants().allowsInRegion(REVIEW, q.region())) {
                    page.add(q.objection());
                }
            }
            if (batch.size() < QUEUE_BATCH) {
                break;
            }
            Objection last = batch.get(batch.size() - 1).objection();
            after = last.submittedAt();
            afterId = last.id();
        }
        return page;
    }

    @Transactional
    public Objection decide(UUID id, Objection.Decision decision, String reason, Caller caller) {
        return decide(id, decision, reason, null, caller);
    }

    /**
     * Decides a pending objection (VF-051): ACCEPT cancels the violation, REDUCE lowers its fine to
     * {@code reducedFineMinor} within the reviewer's FINE_REDUCTION_AMOUNT approval limit (CC-003), REJECT lets it
     * stand, REFER_TO_COURT leaves payment paused.
     */
    @Transactional
    public Objection decide(UUID id, Objection.Decision decision, String reason, Long reducedFineMinor,
                            Caller caller) {
        if (!caller.grants().holds(REVIEW)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "OBJECTION_REVIEW_FORBIDDEN",
                    "cannot decide an objection", List.of("VF-051"));
        }
        Objection current = objections.lock(id).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "OBJECTION_NOT_FOUND", "objection " + id + " not found",
                List.of("VF-051")));
        if (inboundPartner(current.violationNumber()).isPresent()) {
            throw DomainException.conflict("OBJECTION_DECIDED_BY_PARTNER",
                    "the partner state that issued the violation decides this objection", List.of("XB-005"));
        }
        violations.requireReviewer(current.violationNumber(), caller);
        if (decision == Objection.Decision.REDUCE && links.byNumber(current.violationNumber()).isPresent()) {
            // Q-082 (b): the fine of a violation sent to a partner state is collected there; no reduction here.
            throw DomainException.conflict("REDUCTION_NOT_FOR_CROSSBORDER",
                    "the fine of a cross-border violation cannot be reduced here", List.of("VF-051", "XB-001"));
        }
        Objection result = current.decide(decision, caller.actor().id(), clock.instant(), reason, reducedFineMinor);
        objections.saveDecision(result);
        if (decision == Objection.Decision.REDUCE) {
            // The original payable is replaced by one at the reduced fine, with no objection hold (VF-051, VF-052).
            violations.reduceFromObjection(current.violationNumber(), reducedFineMinor, reason, caller);
            // Q-082 (a): the violation stands at the lower fine, so it is confirmed and its points added (VF-030).
            violations.confirmAfterReduction(current.violationNumber());
        } else if (decision == Objection.Decision.ACCEPT) {
            // The accepted challenge withdraws the original payable and cancels its point entry.
            violations.cancelFromObjection(current.violationNumber(), "OBJECTION_ACCEPTED", reason, caller);
            violations.resumeAfterObjection(current.violationNumber(), "objection accepted", caller);
        } else if (decision == Objection.Decision.REJECT) {
            violations.resumeAfterObjection(current.violationNumber(), "objection rejected: " + reason, caller);
            // Q-001: a rejected objection confirms the violation; its black points are added now (VF-030).
            violations.confirmAfterRejection(current.violationNumber());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", result.status().name());
        after.put("decision", decision.name());
        if (reducedFineMinor != null) {
            after.put("reducedFineMinor", reducedFineMinor);
        }
        audit.record("VIOLATION_OBJECTION_DECIDED", "Violation", current.violationNumber(),
                Map.of("status", "PENDING"), after, reason);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("violationNumber", current.violationNumber());
        event.put("decision", decision.name());
        if (reducedFineMinor != null) {
            event.put("reducedFineMinor", reducedFineMinor);
        }
        events.record("itms.violation.objection-decided", current.violationNumber(), event);
        return result;
    }
}

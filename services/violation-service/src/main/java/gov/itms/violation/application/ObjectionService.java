package gov.itms.violation.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.violation.domain.DomainException;
import gov.itms.violation.domain.Objection;
import gov.itms.violation.domain.Violation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Customer objections and the supervisor review queue (VF-050..VF-052). */
public class ObjectionService {

    public static final String SUBMIT = "violation.objection.submit";
    public static final String REVIEW = "violation.objection.review";

    private final JdbcClient jdbc;
    private final ViolationService violations;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;
    private final Duration objectionWindow;

    public ObjectionService(JdbcClient jdbc, ViolationService violations, AuditRecorder audit,
                            DomainEventRecorder events, Clock clock, Duration objectionWindow) {
        this.jdbc = jdbc;
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
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "OBJECTION_SUBMIT_FORBIDDEN", "cannot submit an objection",
                    List.of("VF-050"));
        }
        Violation violation = violations.getForObjection(number, caller);
        Instant now = clock.instant();
        if (now.isAfter(violation.recordedAt().plus(objectionWindow))) {
            throw DomainException.validation("OBJECTION_WINDOW_CLOSED", "the objection period has ended",
                    List.of("VF-050"), violation.recordedAt().plus(objectionWindow).toString());
        }
        if (violation.status() != Violation.Status.OPEN) {
            throw DomainException.conflict("VIOLATION_NOT_OPEN", "only an open violation can be challenged",
                    List.of("VF-050"));
        }
        List<String> evidence = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        Objection objection = new Objection(UUID.randomUUID(), violation.id(), number, caller.actor().id(), now,
                reasonCode, explanation, evidence, Objection.Status.PENDING, null, null, null, null);
        try {
            jdbc.sql("""
                    insert into violation_objection (id, violation_id, violation_number, submitted_by, submitted_at,
                        reason_code, explanation, evidence_ids, status)
                    values (:id, :vid, :number, :by, :at, :reason, :explanation, :evidence, 'PENDING')
                    """).param("id", objection.id()).param("vid", objection.violationId())
                    .param("number", objection.violationNumber()).param("by", objection.submittedBy())
                    .param("at", objection.submittedAt()).param("reason", objection.reasonCode())
                    .param("explanation", objection.explanation()).param("evidence", evidence.toArray(String[]::new))
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw DomainException.conflict("OBJECTION_EXISTS", "an objection has already been submitted",
                    List.of("VF-050"));
        }
        violations.pauseForObjection(number, caller);
        audit.record("VIOLATION_OBJECTION_SUBMITTED", "Violation", number, null,
                java.util.Map.of("objectionId", objection.id().toString(), "reasonCode", reasonCode,
                        "evidenceCount", evidence.size()), null);
        events.record("itms.violation.objection-submitted", number,
                java.util.Map.of("violationNumber", number, "objectionId", objection.id().toString()));
        // XB-005: an objection to a partner-state violation is sent to the issuing state, which decides it.
        inboundPartner(violation.id()).ifPresent(partner -> events.record("itms.violation.crossborder-objection",
                number, java.util.Map.of("violationNumber", number, "partner", partner, "objectionId",
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
        gov.itms.platform.security.CurrentActor system = gov.itms.platform.security.CurrentActor.system(
                CrossborderViolations.SYSTEM);
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
        if (refusal == null) {
            int inserted = jdbc.sql("""
                    insert into violation_objection (id, violation_id, violation_number, submitted_by, submitted_at,
                        reason_code, explanation, evidence_ids, status)
                    values (:id, :vid, :number, :by, :at, 'PARTNER_OBJECTION', :explanation, '{}', 'PENDING')
                    on conflict do nothing
                    """).param("id", UUID.randomUUID()).param("vid", violation.id()).param("number", number)
                    .param("by", "partner:" + partner).param("at", now).param("explanation", text).update();
            if (inserted == 0) {
                refusal = "OBJECTION_EXISTS";
            }
        }
        if (refusal != null) {
            audit.recordAs(system, "VIOLATION_PARTNER_OBJECTION_REFUSED", "Violation", number, null,
                    java.util.Map.of("partner", partner, "reasonCode", refusal), null);
            events.recordAs(system, "itms.violation.objection-decided", number, java.util.Map.of(
                    "violationNumber", number, "decision", "REJECT", "reason", refusal));
            return false;
        }
        audit.recordAs(system, "VIOLATION_OBJECTION_SUBMITTED", "Violation", number, null,
                java.util.Map.of("partner", partner, "reasonCode", "PARTNER_OBJECTION"), null);
        events.recordAs(system, "itms.violation.objection-submitted", number,
                java.util.Map.of("violationNumber", number, "partner", partner));
        return true;
    }

    /** Records the issuing partner state's decision on the pending objection to its violation (XB-005). */
    @Transactional
    public boolean decidedByPartner(String number, String partner, boolean accepted, String reason) {
        String text = reason == null || reason.isBlank() ? "decided by partner state " + partner
                : reason.length() > 500 ? reason.substring(0, 500) : reason;
        int updated = jdbc.sql("""
                update violation_objection set status = :status, decided_by = :by, decided_at = :at,
                    decision = :decision, decision_reason = :reason
                where violation_number = :number and status = 'PENDING'
                """).param("status", accepted ? "ACCEPTED" : "REJECTED").param("by", "partner:" + partner)
                .param("at", clock.instant()).param("decision", accepted ? "ACCEPT" : "REJECT").param("reason", text)
                .param("number", number).update();
        if (updated > 0) {
            audit.recordAs(gov.itms.platform.security.CurrentActor.system(CrossborderViolations.SYSTEM),
                    "VIOLATION_OBJECTION_DECIDED", "Violation", number, java.util.Map.of("status", "PENDING"),
                    java.util.Map.of("status", accepted ? "ACCEPTED" : "REJECTED", "partner", partner), text);
        }
        return updated > 0;
    }

    private java.util.Optional<String> inboundPartner(UUID violationId) {
        return jdbc.sql("select partner_code from violation_crossborder where violation_id = :id"
                        + " and direction = 'INBOUND'").param("id", violationId).query(String.class).optional();
    }

    @Transactional(readOnly = true)
    public List<Objection> queue(int limit, Caller caller) {
        if (!caller.grants().holds(REVIEW)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "OBJECTION_REVIEW_FORBIDDEN", "cannot view the objection queue",
                    List.of("VF-051"));
        }
        if (limit < 1 || limit > 500) {
            throw DomainException.validation("PAGE_INVALID", "limit must be 1..500", List.of("VF-051"));
        }
        // Objections to partner-state violations are decided by the issuing state (XB-005), not in this queue.
        return jdbc.sql("""
                select o.* from violation_objection o where o.status = 'PENDING' and not exists (
                    select 1 from violation_crossborder x where x.violation_id = o.violation_id
                    and x.direction = 'INBOUND')
                order by o.submitted_at limit :n
                """).param("n", limit).query((rs, row) -> map(rs)).list();
    }

    @Transactional
    public Objection decide(UUID id, Objection.Decision decision, String reason, Caller caller) {
        if (!caller.grants().holds(REVIEW)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "OBJECTION_REVIEW_FORBIDDEN", "cannot decide an objection",
                    List.of("VF-051"));
        }
        Objection current = jdbc.sql("select * from violation_objection where id=:id for update").param("id", id)
                .query((rs, row) -> map(rs)).optional().orElseThrow(() -> new DomainException(
                        DomainException.Kind.NOT_FOUND, "OBJECTION_NOT_FOUND", "objection " + id + " not found",
                        List.of("VF-051")));
        if (inboundPartner(current.violationId()).isPresent()) {
            throw DomainException.conflict("OBJECTION_DECIDED_BY_PARTNER",
                    "the partner state that issued the violation decides this objection", List.of("XB-005"));
        }
        Objection result = current.decide(decision, caller.actor().id(), clock.instant(), reason);
        jdbc.sql("update violation_objection set status=:status, decided_by=:by, decided_at=:at, decision=:decision,"
                        + " decision_reason=:reason where id=:id")
                .param("status", result.status().name()).param("by", result.decidedBy()).param("at", result.decidedAt())
                .param("decision", result.decision().name()).param("reason", result.decisionReason())
                .param("id", id).update();
        if (decision == Objection.Decision.ACCEPT) {
            // The accepted challenge withdraws the original payable and cancels its point entry.
            violations.cancelFromObjection(current.violationNumber(), "OBJECTION_ACCEPTED", reason, caller);
            violations.resumeAfterObjection(current.violationNumber(), "objection accepted", caller);
        } else if (decision == Objection.Decision.REJECT) {
            violations.resumeAfterObjection(current.violationNumber(), "objection rejected: " + reason, caller);
        }
        audit.record("VIOLATION_OBJECTION_DECIDED", "Violation", current.violationNumber(),
                java.util.Map.of("status", "PENDING"), java.util.Map.of("status", result.status().name()), reason);
        events.record("itms.violation.objection-decided", current.violationNumber(),
                java.util.Map.of("violationNumber", current.violationNumber(), "decision", decision.name()));
        return result;
    }

    private static Objection map(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Array array = rs.getArray("evidence_ids");
        String[] values = array == null ? new String[0] : (String[]) array.getArray();
        Objection.Status status = Objection.Status.valueOf(rs.getString("status"));
        String decision = rs.getString("decision");
        java.sql.Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new Objection(rs.getObject("id", UUID.class), rs.getObject("violation_id", UUID.class),
                rs.getString("violation_number"), rs.getString("submitted_by"), rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("reason_code"), rs.getString("explanation"), List.of(values), status,
                rs.getString("decided_by"), decidedAt == null ? null : decidedAt.toInstant(),
                decision == null ? null : Objection.Decision.valueOf(decision), rs.getString("decision_reason"));
    }
}

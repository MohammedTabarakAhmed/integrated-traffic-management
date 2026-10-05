package gov.itms.hold.application;

import gov.itms.hold.domain.DomainException;
import gov.itms.hold.domain.Eligibility;
import gov.itms.hold.domain.EligibilityPolicy;
import gov.itms.hold.domain.Hold;
import gov.itms.hold.domain.HoldOverride;
import gov.itms.hold.domain.Subject;
import gov.itms.platform.audit.AuditRecorder;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Eligibility before submission (CC-032; ADR-034). A check needs {@value #CHECK}; it reads every hold on the subjects,
 * whatever their module, because the answer shows no hold details: only the customer-safe reason and next step of
 * {@link EligibilityPolicy}. When the check names the transaction it is for, holds a supervisor overrode for that
 * transaction do not block (CC-034). Each subject checked is audited (CC-120), with the holds that blocked it and the
 * overridden holds that did not.
 */
public class EligibilityService {

    public static final String CHECK = "eligibility.check";
    /** Audit entity of a check: the subject whose holds were evaluated (same id form as {@code SubjectHolds}). */
    static final String ENTITY = "SubjectEligibility";

    private final HoldRepository holds;
    private final EligibilityPolicy policy;
    private final AuditRecorder audit;
    private final Clock clock;

    public EligibilityService(HoldRepository holds, EligibilityPolicy policy, AuditRecorder audit, Clock clock) {
        this.holds = holds;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Eligibility check(String serviceCode, List<Subject> subjects, Caller caller) {
        return check(serviceCode, subjects, null, caller);
    }

    /**
     * {@code transactionReference} (optional) names the transaction the check is for; its service must be
     * {@code serviceCode}.
     */
    @Transactional
    public Eligibility check(String serviceCode, List<Subject> subjects, String transactionReference, Caller caller) {
        if (!caller.grants().allows(CHECK, null)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not check eligibility", List.of("CC-001"));
        }
        List<Subject> asked = Eligibility.request(serviceCode, subjects);
        Set<UUID> overridden = Set.of();
        if (transactionReference != null) {
            if (!HoldOverride.serviceOf(transactionReference).equals(serviceCode)) {
                throw new DomainException(DomainException.Kind.VALIDATION, "TRANSACTION_SERVICE_MISMATCH",
                        "the transaction reference is not one of service " + serviceCode, List.of("CC-032", "CC-034"),
                        serviceCode);
            }
            overridden = holds.overriddenFor(transactionReference);
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
        List<Hold> all = new ArrayList<>();
        for (Subject subject : asked) {
            List<Hold> onSubject = holds.findBySubject(subject);
            all.addAll(onSubject);
            Eligibility own = Eligibility.evaluate(serviceCode, onSubject, overridden, policy, today);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("serviceCode", serviceCode);
            result.put("checkedOn", today.toString());
            result.put("eligible", own.eligible());
            result.put("blockingHolds", own.blockingHolds().stream().map(Object::toString).toList());
            if (transactionReference != null) {
                result.put("transactionReference", transactionReference);
                result.put("overriddenHolds", own.overriddenHolds().stream().map(Object::toString).toList());
            }
            audit.record("ELIGIBILITY_CHECKED", ENTITY, subject.type() + ":" + subject.id(), null, result, null);
        }
        return Eligibility.evaluate(serviceCode, all, overridden, policy, today);
    }
}

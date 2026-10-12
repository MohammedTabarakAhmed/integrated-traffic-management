package gov.itms.violation.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.violation.domain.DomainException;
import gov.itms.violation.domain.Violation;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in customer's own violations and black points (ADR-067 d; CH-002 dashboard, VF-070 and VF-050 online).
 * The file is the one traffic-file-service finds for the national id in the customer's token, never one named in the
 * request; every view is audited like a staff view (CC-120).
 */
public class CustomerViolations {

    /** Held only through the implicit CUSTOMER role (iam V59). */
    public static final String CUSTOMER_READ = "customer.violation.read";

    private final Ports.ViolationRepository repo;
    private final Ports.OwnFile ownFile;
    private final ViolationService violations;
    private final ObjectionService objections;
    private final InstalmentService instalments;
    private final AuditRecorder audit;

    public CustomerViolations(Ports.ViolationRepository repo, Ports.OwnFile ownFile, ViolationService violations,
                              ObjectionService objections, InstalmentService instalments, AuditRecorder audit) {
        this.repo = repo;
        this.ownFile = ownFile;
        this.violations = violations;
        this.objections = objections;
        this.instalments = instalments;
        this.audit = audit;
    }

    /** An own instalment plan for some of the customer's own open violations (VF-071). */
    public gov.itms.violation.domain.InstalmentPlan requestPlan(List<String> violationNumbers, int months,
                                                                Caller caller) {
        return instalments.createOwn(fileNumber(), violationNumbers, months, caller);
    }

    /** The customer's own instalment plans, newest first. */
    public List<gov.itms.violation.domain.InstalmentPlan> plans(int limit, Caller caller) {
        require(caller);
        return instalments.ownPlans(fileNumber(), limit, caller);
    }

    /** An objection to one of the customer's own violations, submitted online (VF-050, XB-005). */
    public gov.itms.violation.domain.Objection object(String number, String reasonCode, String explanation,
                                                       List<String> evidenceIds, Caller caller) {
        return objections.submitOwn(number, fileNumber(), reasonCode, explanation, evidenceIds, caller);
    }

    /** The customer's violations, newest first. */
    @Transactional
    public List<Violation> violations(Violation.Status status, int limit, Caller caller) {
        require(caller);
        if (limit < 1 || limit > 500) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1..500", List.of("VF-016"), "limit");
        }
        String fileNumber = fileNumber();
        audit.record("VIOLATIONS_LISTED", "TrafficFile", fileNumber, null, Map.of("viewer", "OWNER"), null);
        return repo.byFile(fileNumber, status, limit);
    }

    /** The customer's black points, active total, threshold and suspensions (VF-030..VF-034). */
    @Transactional
    public ViolationService.Standing standing(Caller caller) {
        require(caller);
        String fileNumber = fileNumber();
        audit.record("BLACK_POINTS_VIEWED", "BlackPointAccount", fileNumber, null, Map.of("viewer", "OWNER"), null);
        return violations.standingOf(fileNumber);
    }

    private String fileNumber() {
        return ownFile.fileNumber().orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                "OWN_FILE_NOT_FOUND", "you have no traffic file yet", List.of("DL-001")));
    }

    private static void require(Caller caller) {
        if (!caller.grants().holds(CUSTOMER_READ)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not read your violations here", List.of("CC-001"));
        }
    }
}

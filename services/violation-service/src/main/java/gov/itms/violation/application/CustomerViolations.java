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
    private final AuditRecorder audit;

    public CustomerViolations(Ports.ViolationRepository repo, Ports.OwnFile ownFile, ViolationService violations,
                              AuditRecorder audit) {
        this.repo = repo;
        this.ownFile = ownFile;
        this.violations = violations;
        this.audit = audit;
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

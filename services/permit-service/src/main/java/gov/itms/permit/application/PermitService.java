package gov.itms.permit.application;

import gov.itms.permit.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Training and hire permit rules and lifecycle (TR-001..TR-008). */
public class PermitService {
    public record PermitType(String code, String category, String licenceClass, int minimumDrivingYears,
                             int minimumAge, int cleanRecordDays, boolean medicalFitRequired,
                             boolean testRequired, int validityDays, int renewalWindowDays, long feeMinor,
                             String status) { }
    public record Permit(UUID id, String number, String typeCode, String trafficFileNumber, String schoolId,
                         String vehiclePlate, String status, Instant appliedAt, Instant validFrom, Instant validTo) { }
    public record PermitTest(UUID id, UUID permitId, String committee, String examiner, Instant scheduledAt,
                             String result, String reason) { }

    private final Ports.PermitRepository repo;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public PermitService(Ports.PermitRepository repo, AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public PermitType configureType(String code, String category, String licenceClass, int years, int age,
                                    int cleanDays, boolean medical, boolean test, int validityDays,
                                    int renewalWindowDays, long feeMinor, String actor) {
        if (years < 0 || age < 16 || cleanDays < 0 || validityDays < 1 || renewalWindowDays < 0 || feeMinor < 0) {
            throw invalid("PERMIT_TYPE_INVALID", "permit eligibility and validity settings are invalid", "TR-002", "TR-007");
        }
        PermitType type = new PermitType(code, category, licenceClass, years, age, cleanDays, medical, test,
                validityDays, renewalWindowDays, feeMinor, "ACTIVE");
        repo.saveType(type, actor, clock.instant());
        audit.record("PERMIT_TYPE_CONFIGURED", "PermitType", code, null, Map.of("category", category), null);
        return type;
    }

    @Transactional
    public Permit apply(String typeCode, String trafficFile, String schoolId, String vehiclePlate,
                        boolean licenceEligible, int drivingYears, int age, int cleanRecordDays,
                        boolean medicallyFit, boolean dualControls, String exceptionReason, String actor) {
        PermitType type = repo.type(typeCode).orElseThrow(() -> missing("PERMIT_TYPE_NOT_FOUND", "permit type does not exist", "TR-002"));
        if (!"ACTIVE".equals(type.status())) throw invalid("PERMIT_TYPE_INACTIVE", "permit type is inactive", "TR-007");
        boolean eligible = licenceEligible && drivingYears >= type.minimumDrivingYears() && age >= type.minimumAge()
                && cleanRecordDays >= type.cleanRecordDays() && (!type.medicalFitRequired() || medicallyFit);
        if (!eligible && (exceptionReason == null || exceptionReason.isBlank() || exceptionReason.length() > 500)) {
            throw invalid("PERMIT_INELIGIBLE", "applicant does not meet permit type eligibility", "TR-002");
        }
        if ("INSTRUCTOR".equalsIgnoreCase(type.category()) && (schoolId == null || schoolId.isBlank()))
            throw invalid("PERMIT_SCHOOL_REQUIRED", "instructor permit must link a driving school", "TR-005");
        if ("TRAINING_VEHICLE".equalsIgnoreCase(type.category())
                && ((vehiclePlate == null || vehiclePlate.isBlank()) || !dualControls))
            throw invalid("TRAINING_VEHICLE_INVALID", "training vehicle permit requires a registered dual-control vehicle", "TR-005");
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String number = repo.nextNumber(now);
        String status = type.testRequired() ? "APPLIED" : "APPROVED";
        Permit permit = new Permit(id, number, typeCode, trafficFile, schoolId, vehiclePlate, status, now, null, null);
        repo.insertPermit(permit, exceptionReason, actor);
        audit.record("TRAINING_PERMIT_APPLIED", "Permit", id.toString(), null, Map.of("number", number, "status", status), exceptionReason);
        return permit;
    }

    @Transactional
    public PermitTest bookTest(UUID permitId, String committee, Instant scheduledAt, String actor) {
        Permit permit = get(permitId);
        if (!"APPLIED".equals(permit.status()) || scheduledAt == null || !scheduledAt.isAfter(clock.instant()))
            throw invalid("PERMIT_TEST_INVALID", "test requires an applied permit and a future slot", "TR-003");
        PermitTest test = new PermitTest(UUID.randomUUID(), permitId, committee, null, scheduledAt, "BOOKED", null);
        repo.insertTest(test, actor);
        audit.record("PERMIT_TEST_BOOKED", "Permit", permitId.toString(), Map.of("status", permit.status()),
                Map.of("testId", test.id().toString(), "committee", committee), null);
        return test;
    }

    @Transactional
    public Permit recordTest(UUID permitId, String examiner, boolean passed, String reason, String actor) {
        Permit permit = get(permitId);
        PermitTest test = repo.openTest(permitId).orElseThrow(() -> missing("PERMIT_TEST_NOT_FOUND", "booked permit test does not exist", "TR-003"));
        if (examiner == null || examiner.isBlank() || (reason != null && reason.length() > 500))
            throw invalid("PERMIT_TEST_RESULT_INVALID", "test result requires an examiner and bounded reason", "TR-003");
        String result = passed ? "PASSED" : "FAILED";
        repo.recordTest(test.id(), examiner, result, reason, clock.instant());
        String status = passed ? "TESTED" : "APPLIED";
        repo.setStatus(permitId, status, actor, reason, clock.instant());
        audit.record("PERMIT_TEST_RECORDED", "Permit", permitId.toString(), Map.of("status", permit.status()),
                Map.of("status", status, "result", result), reason);
        return get(permitId);
    }

    @Transactional
    public Permit transition(UUID id, String action, String reason, String actor) {
        Permit permit = get(id);
        String target = switch (action) {
            case "approve" -> "APPROVED";
            case "issue", "renew" -> "ISSUED";
            case "suspend" -> "SUSPENDED";
            case "reinstate" -> "ISSUED";
            case "cancel" -> "CANCELLED";
            case "expire" -> "EXPIRED";
            default -> throw invalid("PERMIT_ACTION_INVALID", "unsupported permit transition", "TR-001");
        };
        boolean allowed = switch (target) {
            case "APPROVED" -> "TESTED".equals(permit.status()) || "APPLIED".equals(permit.status());
            case "ISSUED" -> "APPROVED".equals(permit.status()) || "SUSPENDED".equals(permit.status())
                    || ("ISSUED".equals(permit.status()) && "renew".equals(action));
            case "SUSPENDED", "CANCELLED", "EXPIRED" -> "ISSUED".equals(permit.status());
            default -> false;
        };
        if (!allowed || (("suspend".equals(action) || "cancel".equals(action) || "approve".equals(action))
                && (reason == null || reason.isBlank())))
            throw invalid("PERMIT_TRANSITION_INVALID", "permit cannot make the requested transition", "TR-001", "TR-006");
        Instant now = clock.instant();
        Instant validTo = "ISSUED".equals(target) ? now.plusSeconds(repo.type(permit.typeCode()).orElseThrow().validityDays() * 86400L) : null;
        repo.transition(id, target, "ISSUED".equals(target) ? now : null, validTo, actor, reason, now);
        audit.record("TRAINING_PERMIT_" + target, "Permit", id.toString(), Map.of("status", permit.status()), Map.of("status", target), reason);
        events.record("itms.permit.status-changed", id.toString(), Map.of("permitId", id.toString(), "number", permit.number(), "status", target));
        return get(id);
    }

    @Transactional
    public int suspendByTrafficEvent(String trafficFile, String reason) {
        List<Permit> permits = repo.activeByTrafficFile(trafficFile);
        int count = 0;
        for (Permit permit : permits) {
            repo.transition(permit.id(), "SUSPENDED", null, null, "SYSTEM", reason, clock.instant());
            audit.record("TRAINING_PERMIT_SUSPENDED", "Permit", permit.id().toString(), Map.of("status", "ISSUED"),
                    Map.of("status", "SUSPENDED"), reason);
            events.record("itms.permit.status-changed", permit.id().toString(), Map.of("permitId", permit.id().toString(),
                    "number", permit.number(), "status", "SUSPENDED", "reason", reason));
            count++;
        }
        return count;
    }

    /** Expires permits whose validity has elapsed; row locks make overlapping scheduler runs safe. */
    @Transactional
    public int expireDue(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw invalid("PERMIT_EXPIRY_BATCH_INVALID", "expiry batch size must be 1..1000", "TR-001");
        }
        Instant now = clock.instant();
        List<Permit> due = repo.expired(now, batchSize);
        for (Permit permit : due) {
            repo.transition(permit.id(), "EXPIRED", null, null, "SYSTEM", "validity period elapsed", now);
            audit.record("TRAINING_PERMIT_EXPIRED", "Permit", permit.id().toString(),
                    Map.of("status", "ISSUED", "validTo", permit.validTo().toString()),
                    Map.of("status", "EXPIRED"), "validity period elapsed");
            events.record("itms.permit.status-changed", permit.id().toString(), Map.of(
                    "permitId", permit.id().toString(), "number", permit.number(), "status", "EXPIRED"));
        }
        return due.size();
    }

    @Transactional(readOnly = true)
    public Permit get(UUID id) {
        return repo.permit(id).orElseThrow(() -> missing("PERMIT_NOT_FOUND", "permit does not exist", "TR-001"));
    }

    private static DomainException invalid(String code, String message, String... ids) {
        return new DomainException(DomainException.Kind.VALIDATION, code, message, List.of(ids));
    }
    private static DomainException missing(String code, String message, String... ids) {
        return new DomainException(DomainException.Kind.NOT_FOUND, code, message, List.of(ids));
    }
}

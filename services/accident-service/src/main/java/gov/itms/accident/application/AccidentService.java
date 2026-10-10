package gov.itms.accident.application;

import gov.itms.accident.domain.Accident;
import gov.itms.accident.domain.DomainException;
import gov.itms.accident.domain.RepairPermit;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.transaction.annotation.Transactional;

/** Accident report lifecycle and fault determination (AC-001..AC-006). */
public class AccidentService {

    public record PartyInput(String trafficFileNumber, String vehiclePlate, Accident.Role role, String seatPosition,
                             String injuryDegree, String transportMethod, String intoxicationStatus,
                             Integer faultPercent, boolean agrees, boolean unknownParty) { }
    public record Report(String region, Instant occurredAt, String location, String roadCondition,
                         String weather, Accident.Origin origin, List<PartyInput> parties,
                         List<String> evidenceDocumentIds) { }

    private final Ports.AccidentRepository repo;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AccidentService(Ports.AccidentRepository repo, AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Accident report(Report report, String actorId) {
        if (report.parties() == null || report.parties().size() < 1 || report.parties().size() > 50
                || report.evidenceDocumentIds() == null || report.evidenceDocumentIds().size() > 100
                || report.parties().stream().anyMatch(p -> (p.trafficFileNumber() == null && p.vehiclePlate() == null)
                && !(p.unknownParty() && p.role() == Accident.Role.DRIVER))) {
            throw new DomainException(DomainException.Kind.VALIDATION, "ACCIDENT_REPORT_INVALID",
                    "an accident needs identified parties and bounded evidence", List.of("AC-001"));
        }
        boolean agreed = report.parties().stream().allMatch(PartyInput::agrees)
                && report.parties().stream().allMatch(p -> p.faultPercent() != null);
        if (agreed && report.parties().stream().mapToInt(PartyInput::faultPercent).sum() != 100) {
            throw new DomainException(DomainException.Kind.VALIDATION, "FAULT_SHARES_INVALID",
                    "agreed fault percentages must total 100", List.of("AC-002"));
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String number = repo.nextReportNumber(report.occurredAt());
        String status = agreed ? Accident.Status.FAULT_DECIDED.name() : Accident.Status.UNDER_INVESTIGATION.name();
        repo.insert(id, number, status, report, actorId, now);
        audit.record("ACCIDENT_REPORTED", "Accident", id.toString(), null,
                Map.of("reportNumber", number, "status", status, "partyCount", report.parties().size()), null);
        events.record("itms.accident.reported", id.toString(), Map.of("accidentId", id.toString(),
                "reportNumber", number, "status", status, "region", report.region()));
        if (agreed) {
            events.record("itms.accident.fault-decided", id.toString(), Map.of("accidentId", id.toString(),
                    "reportNumber", number, "region", report.region()));
        }
        return get(id);
    }

    @Transactional(readOnly = true)
    public Accident get(UUID id) {
        return repo.find(id).orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                "ACCIDENT_NOT_FOUND", "accident report does not exist", List.of("AC-005")));
    }

    @Transactional
    public Accident decideFault(UUID id, Map<Integer, Integer> shares, String reason, String actorId) {
        Accident accident = get(id);
        if (!accident.status().equals(Accident.Status.REPORTED.name())
                && !accident.status().equals(Accident.Status.UNDER_INVESTIGATION.name())) {
            throw new DomainException(DomainException.Kind.CONFLICT, "ACCIDENT_NOT_OPEN",
                    "fault can only be decided on an open investigation", List.of("AC-005"));
        }
        if (reason == null || reason.isBlank() || reason.length() > 500 || shares == null
                || shares.size() != accident.parties().size()
                || shares.values().stream().anyMatch(p -> p == null || p < 0 || p > 100)
                || shares.values().stream().mapToInt(Integer::intValue).sum() != 100
                || shares.keySet().stream().anyMatch(no -> accident.parties().stream()
                .noneMatch(p -> p.partyNo() == no))) {
            throw new DomainException(DomainException.Kind.VALIDATION, "FAULT_SHARES_INVALID",
                    "provide a reason and one fault share per party, totaling 100", List.of("AC-005"));
        }
        repo.decideFault(id, shares, reason.strip(), actorId, clock.instant());
        audit.record("ACCIDENT_FAULT_DECIDED", "Accident", id.toString(), Map.of("status", accident.status()),
                Map.of("status", Accident.Status.FAULT_DECIDED.name(), "faultShares", shares), reason);
        events.record("itms.accident.fault-decided", id.toString(), Map.of("accidentId", id.toString(),
                "reportNumber", accident.reportNumber(), "region", accident.region(), "faultShares", shares));
        return get(id);
    }

    @Transactional
    public IssuedRepairPermit issueRepairPermit(UUID accidentId, String vehiclePlate, List<String> damagedParts,
                                                 String actorId) {
        Accident accident = get(accidentId);
        if (!accident.status().equals(Accident.Status.FAULT_DECIDED.name())
                && !accident.status().equals(Accident.Status.CLOSED.name())) {
            throw new DomainException(DomainException.Kind.CONFLICT, "ACCIDENT_FAULT_PENDING",
                    "fault must be decided before a repair permit is issued", List.of("AC-020"));
        }
        if (accident.parties().stream().noneMatch(p -> vehiclePlate.equals(p.vehiclePlate()))) {
            throw new DomainException(DomainException.Kind.VALIDATION, "ACCIDENT_VEHICLE_MISMATCH",
                    "the vehicle is not listed in this accident", List.of("AC-020"));
        }
        if (damagedParts == null || damagedParts.isEmpty() || damagedParts.size() > 100
                || damagedParts.stream().anyMatch(p -> p == null || p.isBlank() || p.length() > 120)) {
            throw new DomainException(DomainException.Kind.VALIDATION, "DAMAGED_PARTS_INVALID",
                    "list 1 to 100 damaged parts", List.of("AC-020"));
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = hash(code);
        UUID permitId = UUID.randomUUID();
        Instant issuedAt = clock.instant();
        String number = "RP-" + issuedAt.atZone(java.time.ZoneOffset.UTC).getYear() + "-"
                + permitId.toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
        RepairPermit permit = new RepairPermit(permitId, number, accident.reportNumber(), vehiclePlate,
                accident.region(), damagedParts, issuedAt, "ISSUED", true);
        repo.insertRepairPermit(permit, hash);
        audit.record("REPAIR_PERMIT_ISSUED", "RepairPermit", permitId.toString(), null,
                Map.of("number", number, "accident", accident.reportNumber(), "vehiclePlate", vehiclePlate,
                        "damagedParts", damagedParts), null);
        events.record("itms.accident.repair-permit-issued", permitId.toString(), Map.of("permitId", permitId.toString(),
                "permitNumber", number, "accidentReportNumber", accident.reportNumber(), "vehiclePlate", vehiclePlate));
        return new IssuedRepairPermit(permit, code);
    }

    public record IssuedRepairPermit(RepairPermit permit, String verificationCode) { }

    @Transactional(readOnly = true)
    public RepairPermit verifyRepairPermit(String number, String code) {
        RepairPermit permit = repo.repairPermit(number).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "REPAIR_PERMIT_NOT_FOUND", "repair permit does not exist",
                List.of("AC-021")));
        return new RepairPermit(permit.id(), permit.number(), permit.accidentReportNumber(), permit.vehiclePlate(),
                permit.region(), permit.damagedParts(), permit.issuedAt(), permit.status(), code != null
                && repo.verifyRepairPermit(number, hash(code)) && "ISSUED".equals(permit.status()));
    }

    @Transactional(readOnly = true)
    public RepairPermit repairPermit(String number) {
        return repo.repairPermit(number).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "REPAIR_PERMIT_NOT_FOUND", "repair permit does not exist",
                List.of("AC-020")));
    }

    @Transactional
    public RepairPermit cancelRepairPermit(String number, String reason, String actorId) {
        RepairPermit permit = repo.repairPermit(number).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "REPAIR_PERMIT_NOT_FOUND", "repair permit does not exist",
                List.of("AC-020")));
        if (!"ISSUED".equals(permit.status()) || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new DomainException(DomainException.Kind.VALIDATION, "REPAIR_PERMIT_CANCELLATION_INVALID",
                    "only an issued permit can be cancelled with a reason", List.of("AC-020"));
        }
        repo.cancelRepairPermit(number, actorId, clock.instant(), reason.strip());
        audit.record("REPAIR_PERMIT_CANCELLED", "RepairPermit", permit.id().toString(),
                Map.of("status", "ISSUED"), Map.of("status", "CANCELLED"), reason);
        events.record("itms.accident.repair-permit-cancelled", permit.id().toString(),
                Map.of("permitId", permit.id().toString(), "permitNumber", number,
                        "accidentReportNumber", permit.accidentReportNumber()));
        return repo.repairPermit(number).orElseThrow();
    }

    private static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }
}

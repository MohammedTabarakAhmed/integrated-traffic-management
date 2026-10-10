package gov.itms.accident.application;

import gov.itms.accident.domain.Accident;
import gov.itms.accident.domain.RepairPermit;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

public final class Ports {
    private Ports() { }
    public interface AccidentRepository {
        String nextReportNumber(Instant occurredAt);
        void insert(UUID id, String reportNumber, String status, AccidentService.Report report,
                    String actorId, Instant recordedAt);
        void decideFault(UUID id, Map<Integer, Integer> faultShares, String reason, String actorId, Instant decidedAt);
        void insertRepairPermit(RepairPermit permit, String codeHash);
        Optional<RepairPermit> repairPermit(String number);
        boolean verifyRepairPermit(String number, String codeHash);
        void cancelRepairPermit(String number, String actorId, Instant at, String reason);
        Optional<Accident> find(UUID id);
    }
}

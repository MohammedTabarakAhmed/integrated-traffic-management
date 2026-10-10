package gov.itms.permit.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class Ports {
    private Ports() { }
    public interface PermitRepository {
        void saveType(PermitService.PermitType type, String actor, Instant at);
        Optional<PermitService.PermitType> type(String code);
        String nextNumber(Instant at);
        void insertPermit(PermitService.Permit permit, String exceptionReason, String actor);
        void insertTest(PermitService.PermitTest test, String actor);
        Optional<PermitService.PermitTest> openTest(UUID permitId);
        void recordTest(UUID testId, String examiner, String result, String reason, Instant at);
        void setStatus(UUID id, String status, String actor, String reason, Instant at);
        void transition(UUID id, String status, Instant validFrom, Instant validTo, String actor, String reason, Instant at);
        Optional<PermitService.Permit> permit(UUID id);
        List<PermitService.Permit> activeByTrafficFile(String trafficFile);
        List<PermitService.Permit> expired(Instant at, int limit);
    }
}

package gov.itms.accident.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class WorkflowPorts {
    private WorkflowPorts() { }
    public interface AccidentWorkflowRepository {
        Optional<Instant> lastFaultDecisionAt(UUID accidentId);
        void insertObjection(AccidentWorkflowService.Objection objection);
        Optional<AccidentWorkflowService.Objection> objection(UUID id);
        void reviewObjection(UUID id,String status,String reviewer,String reason,Instant at);
        int pendingObjections(UUID accidentId);
        void updateHitRun(UUID accidentId,int partyNo,String trafficFile,String vehiclePlate);
        void transition(UUID accidentId,String status,String decisionType,String reason,String actor,Instant at);
        void queueExchange(UUID id,UUID accidentId,String partner,String reference,String payload,Instant at);
        void insertBook(AccidentWorkflowService.PaperBook book);
        Optional<AccidentWorkflowService.PaperBook> book(String serial);
        void insertBookReport(String serial,String reportSerial,UUID accidentId,Instant at);
        java.util.List<String> bookReports(String serial);
        void finishBook(String serial,String status,String actor,Instant at);
    }
}

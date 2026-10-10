package gov.itms.accident.application;

import gov.itms.accident.domain.Accident;
import gov.itms.accident.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Accident objection, hit-and-run and external referral workflows (AC-007..AC-012). */
public class AccidentWorkflowService {
    public record Objection(UUID id,UUID accidentId,int partyNo,String reason,Instant filedAt,Instant deadlineAt,
                            String status,String reviewedBy,Instant reviewedAt,String reviewReason) { }
    public record PaperBook(String serial,String region,String officerId,String status,Instant issuedAt,
                            Instant reconciledAt,String reconciledBy) { }
    public record BookEntry(String bookSerial,String reportSerial,UUID accidentId,Instant enteredAt) { }
    private final Ports.AccidentRepository accidents;
    private final WorkflowPorts.AccidentWorkflowRepository workflow;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration objectionWindow;

    public AccidentWorkflowService(Ports.AccidentRepository accidents,WorkflowPorts.AccidentWorkflowRepository workflow,
                                   AuditRecorder audit,DomainEventRecorder events,ObjectMapper json,Clock clock,
                                   Duration objectionWindow) {
        this.accidents=accidents;this.workflow=workflow;this.audit=audit;this.events=events;this.json=json;
        this.clock=clock;this.objectionWindow=objectionWindow;
        if(objectionWindow.isNegative()||objectionWindow.isZero()||objectionWindow.compareTo(Duration.ofDays(180))>0)
            throw new IllegalArgumentException("itms.accidents.objection-window must be 1..180 days");
    }

    @Transactional(readOnly=true)
    public Objection objection(UUID id) {
        return workflow.objection(id).orElseThrow(()->missing("ACCIDENT_OBJECTION_NOT_FOUND","objection does not exist","AC-009"));
    }

    @Transactional(readOnly=true)
    public PaperBook paperBook(String serial) {
        return workflow.book(serial).orElseThrow(()->missing("ACCIDENT_BOOK_NOT_FOUND","paper book does not exist","AC-022"));
    }

    @Transactional
    public PaperBook issuePaperBook(String serial,String region,String officerId,String actor) {
        if(serial==null||!serial.matches("^[A-Z0-9][A-Z0-9-]{1,39}$")||region==null||region.isBlank()
                ||officerId==null||officerId.isBlank())
            throw invalid("ACCIDENT_BOOK_INVALID","book serial, region and officer are required","AC-022");
        PaperBook book=new PaperBook(serial,region,officerId,"ISSUED",clock.instant(),null,null);
        workflow.insertBook(book);
        audit.record("ACCIDENT_PAPER_BOOK_ISSUED","AccidentPaperBook",serial,null,
                Map.of("region",region,"officerId",officerId,"status","ISSUED"),null);
        events.record("itms.accident.paper-book-issued",serial,Map.of("bookSerial",serial,"region",region,"officerId",officerId));
        return book;
    }

    @Transactional
    public BookEntry enterPaperReport(String serial,String reportSerial,UUID accidentId,String actor) {
        PaperBook book=workflow.book(serial).orElseThrow(()->missing("ACCIDENT_BOOK_NOT_FOUND","paper book does not exist","AC-022"));
        Accident accident=get(accidentId);
        if(!"ISSUED".equals(book.status())||!book.officerId().equals(actor)||!book.region().equals(accident.region())
                ||reportSerial==null||!reportSerial.matches("^[A-Z0-9][A-Z0-9-]{1,39}$"))
            throw invalid("ACCIDENT_BOOK_ENTRY_INVALID","issued book, assigned officer, matching region and report serial are required","AC-022");
        Instant at=clock.instant();workflow.insertBookReport(serial,reportSerial,accidentId,at);
        audit.record("ACCIDENT_PAPER_REPORT_ENTERED","AccidentPaperBook",serial,null,
                Map.of("reportSerial",reportSerial,"accidentId",accidentId.toString()),null);
        events.record("itms.accident.paper-report-entered",accidentId.toString(),Map.of("bookSerial",serial,"reportSerial",reportSerial,"accidentId",accidentId.toString()));
        return new BookEntry(serial,reportSerial,accidentId,at);
    }

    @Transactional
    public PaperBook losePaperBook(String serial,String reason,String actor) {
        PaperBook book=workflow.book(serial).orElseThrow(()->missing("ACCIDENT_BOOK_NOT_FOUND","paper book does not exist","AC-022"));
        if(!"ISSUED".equals(book.status())||!book.officerId().equals(actor)||reason==null||reason.isBlank()||reason.length()>500)
            throw invalid("ACCIDENT_BOOK_LOSS_INVALID","an issued book and loss reason are required","AC-022");
        workflow.finishBook(serial,"LOST",actor,clock.instant());
        audit.record("ACCIDENT_PAPER_BOOK_LOST","AccidentPaperBook",serial,Map.of("status","ISSUED"),Map.of("status","LOST"),reason);
        events.record("itms.accident.paper-book-lost",serial,Map.of("bookSerial",serial,"officerId",book.officerId()));
        return workflow.book(serial).orElseThrow();
    }

    @Transactional
    public PaperBook reconcilePaperBook(String serial,List<String> reportedSerials,String actor) {
        PaperBook book=workflow.book(serial).orElseThrow(()->missing("ACCIDENT_BOOK_NOT_FOUND","paper book does not exist","AC-022"));
        List<String> actual=workflow.bookReports(serial);
        List<String> declared=(reportedSerials==null?List.<String>of():reportedSerials).stream().sorted().toList();
        if(!"ISSUED".equals(book.status())||!book.officerId().equals(actor)||!actual.equals(declared))
            throw invalid("ACCIDENT_BOOK_RECONCILIATION_INVALID","only the assigned officer may reconcile and declared reports must match recorded entries","AC-022");
        Instant at=clock.instant();workflow.finishBook(serial,"RECONCILED",actor,at);
        audit.record("ACCIDENT_PAPER_BOOK_RECONCILED","AccidentPaperBook",serial,Map.of("status","ISSUED"),
                Map.of("status","RECONCILED","reportCount",actual.size()),null);
        events.record("itms.accident.paper-book-reconciled",serial,Map.of("bookSerial",serial,"reportCount",actual.size()));
        return workflow.book(serial).orElseThrow();
    }

    @Transactional
    public Objection object(UUID accidentId,int partyNo,String reason,String actor) {
        Accident accident=get(accidentId);
        if(!Accident.Status.FAULT_DECIDED.name().equals(accident.status())||accident.parties().stream().noneMatch(p->p.partyNo()==partyNo)
                ||reason==null||reason.isBlank()||reason.length()>1000)
            throw invalid("ACCIDENT_OBJECTION_INVALID","a listed party may object to a decided fault within the configured period","AC-009");
        Instant now=clock.instant();Instant decided=workflow.lastFaultDecisionAt(accidentId).orElseThrow(()->invalid(
                "FAULT_DECISION_NOT_FOUND","fault decision history is missing","AC-005"));
        Instant deadline=decided.plus(objectionWindow);
        if(now.isAfter(deadline))throw invalid("ACCIDENT_OBJECTION_LATE","the objection period has expired","AC-009");
        Objection objection=new Objection(UUID.randomUUID(),accidentId,partyNo,reason.strip(),now,deadline,"PENDING",null,null,null);
        workflow.insertObjection(objection);
        audit.record("ACCIDENT_OBJECTION_FILED","AccidentObjection",objection.id().toString(),null,
                Map.of("accidentId",accident.reportNumber(),"partyNo",partyNo,"deadline",deadline.toString()),reason);
        events.record("itms.accident.objection-filed",objection.id().toString(),Map.of("accidentId",accident.id().toString(),
                "reportNumber",accident.reportNumber(),"partyNo",partyNo,"deadlineAt",deadline.toString()));
        return objection;
    }

    @Transactional
    public Objection review(UUID id,boolean accept,String reason,String reviewer) {
        Objection objection=workflow.objection(id).orElseThrow(()->missing("ACCIDENT_OBJECTION_NOT_FOUND","objection does not exist","AC-009"));
        if(!"PENDING".equals(objection.status())||reason==null||reason.isBlank()||reason.length()>1000)
            throw invalid("ACCIDENT_OBJECTION_REVIEW_INVALID","pending objections require a reasoned senior-investigator decision","AC-009");
        String status=accept?"ACCEPTED":"REJECTED";Instant now=clock.instant();
        workflow.reviewObjection(id,status,reviewer,reason.strip(),now);
        if(accept)workflow.transition(objection.accidentId(),"UNDER_INVESTIGATION","OBJECTION",reason.strip(),reviewer,now);
        audit.record("ACCIDENT_OBJECTION_"+status,"AccidentObjection",id.toString(),Map.of("status","PENDING"),
                Map.of("status",status,"reviewer",reviewer),reason);
        events.record("itms.accident.objection-reviewed",id.toString(),Map.of("objectionId",id.toString(),
                "accidentId",objection.accidentId().toString(),"status",status));
        return workflow.objection(id).orElseThrow();
    }

    @Transactional
    public Accident identifyHitRun(UUID id,int partyNo,String file,String plate,String actor) {
        Accident a=get(id);
        if(a.parties().stream().noneMatch(p->p.partyNo()==partyNo&&p.unknownParty())||
                ((file==null||file.isBlank())&&(plate==null||plate.isBlank()))||
                (file!=null&&file.isBlank())||(plate!=null&&plate.isBlank()))
            throw invalid("HIT_RUN_PARTY_INVALID","identify an existing unknown hit-and-run party","AC-008");
        workflow.updateHitRun(id,partyNo,file,plate);
        audit.record("ACCIDENT_HIT_RUN_IDENTIFIED","Accident",id.toString(),Map.of("partyNo",partyNo,"unknownParty",true),
                Map.of("partyNo",partyNo,"trafficFile",file==null?"":file,"vehiclePlate",plate==null?"":plate),null);
        events.record("itms.accident.hit-run-identified",id.toString(),Map.of("accidentId",id.toString(),"partyNo",partyNo,
                "trafficFile",file==null?"":file,"vehiclePlate",plate==null?"":plate));
        return get(id);
    }

    @Transactional
    public Accident close(UUID id,String reason,String actor) {
        Accident a=get(id);Instant decided=workflow.lastFaultDecisionAt(id).orElseThrow(()->invalid("FAULT_DECISION_NOT_FOUND","fault decision history is missing","AC-005"));
        if(!Accident.Status.FAULT_DECIDED.name().equals(a.status())||workflow.pendingObjections(id)>0
                ||clock.instant().isBefore(decided.plus(objectionWindow)))
            throw invalid("ACCIDENT_CLOSE_NOT_ALLOWED","close only after the objection period with no pending objections","AC-005","AC-009");
        workflow.transition(id,"CLOSED","CLOSE",reason==null?"objection period elapsed":reason,actor,clock.instant());
        audit.record("ACCIDENT_CLOSED","Accident",id.toString(),Map.of("status",a.status()),Map.of("status","CLOSED"),reason);
        events.record("itms.accident.closed",id.toString(),Map.of("accidentId",id.toString(),"reportNumber",a.reportNumber()));
        return get(id);
    }

    @Transactional
    public Accident referToProsecution(UUID id,String reason,String actor) {
        Accident a=get(id);
        if((!Accident.Status.UNDER_INVESTIGATION.name().equals(a.status())&&!Accident.Status.FAULT_DECIDED.name().equals(a.status()))
                ||reason==null||reason.isBlank()||reason.length()>1000)
            throw invalid("PROSECUTION_REFERRAL_INVALID","open serious accidents require a referral reason","AC-011");
        workflow.transition(id,"REFERRED_TO_PROSECUTION","PROSECUTION",reason.strip(),actor,clock.instant());
        queuePartnerExchange(a,"PROSECUTION",Map.of("reportNumber",a.reportNumber(),"reason",reason.strip(),
                "evidenceDocumentIds",a.evidenceDocumentIds()));
        audit.record("ACCIDENT_REFERRED_TO_PROSECUTION","Accident",id.toString(),Map.of("status",a.status()),
                Map.of("status","REFERRED_TO_PROSECUTION"),reason);
        events.record("itms.accident.prosecution-referred",id.toString(),Map.of("accidentId",id.toString(),"reportNumber",a.reportNumber()));
        return get(id);
    }

    @Transactional
    public void requestAmbulance(UUID id,String actor) {
        Accident a=get(id);queuePartnerExchange(a,"AMBULANCE",Map.of("reportNumber",a.reportNumber(),"region",a.region(),"occurredAt",a.occurredAt().toString(),"location",a.location()));
        audit.record("ACCIDENT_AMBULANCE_REQUESTED","Accident",id.toString(),null,Map.of("reportNumber",a.reportNumber()),null);
        events.record("itms.accident.ambulance-requested",id.toString(),Map.of("accidentId",id.toString(),"reportNumber",a.reportNumber()));
    }

    @Transactional
    public void sendToInsurers(UUID id,String actor) {
        Accident a=get(id);queuePartnerExchange(a,"INSURER",Map.of("reportNumber",a.reportNumber(),"region",a.region(),"occurredAt",a.occurredAt().toString(),"parties",a.parties()));
        audit.record("ACCIDENT_INSURERS_NOTIFIED","Accident",id.toString(),null,Map.of("reportNumber",a.reportNumber()),null);
        events.record("itms.accident.insurers-notified",id.toString(),Map.of("accidentId",id.toString(),"reportNumber",a.reportNumber()));
    }

    private void queuePartnerExchange(Accident a,String partner,Map<String,?> payload) {
        try { workflow.queueExchange(UUID.randomUUID(),a.id(),partner,a.reportNumber(),json.writeValueAsString(payload),clock.instant()); }
        catch(JacksonException e){throw new IllegalStateException("accident exchange payload could not be encoded",e);}
    }
    private Accident get(UUID id){return accidents.find(id).orElseThrow(()->missing("ACCIDENT_NOT_FOUND","accident report does not exist","AC-005"));}
    private static DomainException invalid(String code,String message,String... ids){return new DomainException(DomainException.Kind.VALIDATION,code,message,List.of(ids));}
    private static DomainException missing(String code,String message,String... ids){return new DomainException(DomainException.Kind.NOT_FOUND,code,message,List.of(ids));}
}

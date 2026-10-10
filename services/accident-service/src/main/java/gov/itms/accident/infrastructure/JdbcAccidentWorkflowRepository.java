package gov.itms.accident.infrastructure;

import gov.itms.accident.application.AccidentWorkflowService;
import gov.itms.accident.application.WorkflowPorts;
import gov.itms.accident.domain.DomainException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcAccidentWorkflowRepository implements WorkflowPorts.AccidentWorkflowRepository {
    private final JdbcClient jdbc;
    public JdbcAccidentWorkflowRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    @Override public Optional<Instant> lastFaultDecisionAt(UUID accidentId){
        return jdbc.sql("select decided_at from accident_decision where accident_id=:id and decision_type='FAULT' order by decided_at desc limit 1")
                .param("id",accidentId).query((rs,row)->rs.getTimestamp(1).toInstant()).optional();
    }
    @Override public void insertObjection(AccidentWorkflowService.Objection o){
        jdbc.sql("insert into accident_objection(id,accident_id,party_no,reason,filed_at,deadline_at,status) values (:id,:accident,:party,:reason,:filed,:deadline,'PENDING')")
                .param("id",o.id()).param("accident",o.accidentId()).param("party",o.partyNo()).param("reason",o.reason()).param("filed",o.filedAt()).param("deadline",o.deadlineAt()).update();
    }
    @Override public Optional<AccidentWorkflowService.Objection> objection(UUID id){
        return jdbc.sql("select * from accident_objection where id=:id").param("id",id).query(JdbcAccidentWorkflowRepository::mapObjection).optional();
    }
    @Override public void reviewObjection(UUID id,String status,String reviewer,String reason,Instant at){
        int count=jdbc.sql("update accident_objection set status=:status,reviewed_by=:reviewer,reviewed_at=:at,review_reason=:reason where id=:id and status='PENDING'")
                .param("status",status).param("reviewer",reviewer).param("at",at).param("reason",reason).param("id",id).update();
        if(count!=1)throw new DomainException(DomainException.Kind.CONFLICT,"ACCIDENT_OBJECTION_CLOSED","objection is no longer pending",List.of("AC-009"));
    }
    @Override public int pendingObjections(UUID id){return jdbc.sql("select count(*) from accident_objection where accident_id=:id and status='PENDING'").param("id",id).query(Integer.class).single();}
    @Override public void updateHitRun(UUID id,int partyNo,String file,String plate){
        int count=jdbc.sql("update accident_party set traffic_file_number=:file,vehicle_plate=:plate,unknown_party=false where accident_id=:id and party_no=:party and unknown_party=true")
                .param("file",file).param("plate",plate).param("id",id).param("party",partyNo).update();
        if(count!=1)throw new DomainException(DomainException.Kind.CONFLICT,"HIT_RUN_PARTY_ALREADY_IDENTIFIED","unknown party has already been identified",List.of("AC-008"));
    }
    @Override public void transition(UUID id,String status,String decisionType,String reason,String actor,Instant at){
        int count=jdbc.sql("update accident_report set status=:status where id=:id and status not in ('CLOSED','REFERRED_TO_PROSECUTION')")
                .param("status",status).param("id",id).update();
        if(count!=1)throw new DomainException(DomainException.Kind.CONFLICT,"ACCIDENT_NOT_OPEN","accident report is already closed",List.of("AC-005"));
        if(decisionType!=null)jdbc.sql("insert into accident_decision(accident_id,decision_type,reason,decided_by,decided_at) values (:id,:type,:reason,:actor,:at)")
                .param("id",id).param("type",decisionType).param("reason",reason==null?"":reason).param("actor",actor).param("at",at).update();
    }
    @Override public void queueExchange(UUID id,UUID accidentId,String partner,String reference,String payload,Instant at){
        jdbc.sql("insert into accident_external_exchange(id,accident_id,partner,direction,reference,payload,status,created_at) values (:id,:accident,:partner,'OUTBOUND',:reference,cast(:payload as jsonb),'QUEUED',:at)")
                .param("id",id).param("accident",accidentId).param("partner",partner).param("reference",reference).param("payload",payload).param("at",at).update();
    }
    @Override public void insertBook(AccidentWorkflowService.PaperBook b){
        jdbc.sql("insert into accident_paper_book(serial,region,officer_id,status,issued_at) values (:serial,:region,:officer,'ISSUED',:at)")
                .param("serial",b.serial()).param("region",b.region()).param("officer",b.officerId()).param("at",b.issuedAt()).update();
    }
    @Override public Optional<AccidentWorkflowService.PaperBook> book(String serial){
        return jdbc.sql("select * from accident_paper_book where serial=:serial").param("serial",serial)
                .query((r,row)->new AccidentWorkflowService.PaperBook(r.getString("serial"),r.getString("region"),r.getString("officer_id"),r.getString("status"),r.getTimestamp("issued_at").toInstant(),instant(r,"reconciled_at"),r.getString("reconciled_by"))).optional();
    }
    @Override public void insertBookReport(String serial,String reportSerial,UUID accidentId,Instant at){
        jdbc.sql("insert into accident_paper_book_report(book_serial,report_serial,accident_id,entered_at) values (:book,:report,:accident,:at)")
                .param("book",serial).param("report",reportSerial).param("accident",accidentId).param("at",at).update();
    }
    @Override public List<String> bookReports(String serial){
        return jdbc.sql("select report_serial from accident_paper_book_report where book_serial=:serial order by report_serial")
                .param("serial",serial).query(String.class).list();
    }
    @Override public void finishBook(String serial,String status,String actor,Instant at){
        int changed=jdbc.sql("update accident_paper_book set status=:status,reconciled_at=case when :status='RECONCILED' then :at else reconciled_at end,reconciled_by=case when :status='RECONCILED' then :actor else reconciled_by end where serial=:serial and status='ISSUED'")
                .param("status",status).param("at",at).param("actor",actor).param("serial",serial).update();
        if(changed!=1)throw new DomainException(DomainException.Kind.CONFLICT,"ACCIDENT_BOOK_CLOSED","paper book is no longer issued",List.of("AC-022"));
    }
    private static AccidentWorkflowService.Objection mapObjection(ResultSet r,int row)throws SQLException{
        return new AccidentWorkflowService.Objection(r.getObject("id",UUID.class),r.getObject("accident_id",UUID.class),r.getInt("party_no"),r.getString("reason"),r.getTimestamp("filed_at").toInstant(),r.getTimestamp("deadline_at").toInstant(),r.getString("status"),r.getString("reviewed_by"),instant(r,"reviewed_at"),r.getString("review_reason"));
    }
    private static Instant instant(ResultSet r,String c)throws SQLException{java.sql.Timestamp t=r.getTimestamp(c);return t==null?null:t.toInstant();}
}

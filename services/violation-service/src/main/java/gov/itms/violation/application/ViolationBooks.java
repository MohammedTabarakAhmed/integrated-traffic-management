package gov.itms.violation.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.violation.domain.DomainException;
import gov.itms.violation.domain.Violation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Paper violation book issuance, field entry and reconciliation (VF-013). */
public class ViolationBooks {
    public record Book(String serial,String region,String officerId,String status,Instant issuedAt,
                       Instant reconciledAt,String reconciledBy) { }
    public record Entry(String bookSerial,String reportSerial,String violationNumber,Instant enteredAt) { }
    private final Ports.ViolationRepository repo;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public ViolationBooks(Ports.ViolationRepository repo,AuditRecorder audit,DomainEventRecorder events,Clock clock) {
        this.repo=repo;this.audit=audit;this.events=events;this.clock=clock;
    }

    @Transactional
    public Book issue(String serial,String region,String officerId,Caller caller) {
        ViolationService.channelOf(caller);
        require(caller,"violation.book.issue",region);
        if(serial==null||!serial.matches("^[A-Z0-9][A-Z0-9-]{1,39}$")||region==null
                ||!region.matches("^[A-Z0-9_-]{1,40}$")||officerId==null||officerId.isBlank())
            throw invalid("VIOLATION_BOOK_INVALID","book serial and assigned officer are required");
        Book book=new Book(serial,region,officerId,"ISSUED",clock.instant(),null,null);
        repo.insertPaperBook(book);
        audit.record("VIOLATION_PAPER_BOOK_ISSUED","ViolationPaperBook",serial,null,
                Map.of("region",region,"officerId",officerId,"status","ISSUED"),null);
        events.record("itms.violation.paper-book-issued",serial,Map.of("bookSerial",serial,"region",region,"officerId",officerId));
        return book;
    }

    @Transactional
    public Entry enter(String serial,String reportSerial,String violationNumber,Caller caller) {
        ViolationService.channelOf(caller);
        Book book=book(serial);
        Violation violation=repo.findByNumber(violationNumber).orElseThrow(()->new DomainException(
                DomainException.Kind.NOT_FOUND,"VIOLATION_NOT_FOUND","violation does not exist",List.of("VF-013")));
        require(caller,"violation.book.record",violation.report().region());
        if(!"ISSUED".equals(book.status())||!book.officerId().equals(caller.actor().id())
                ||!book.region().equals(violation.report().region())||reportSerial==null
                ||!reportSerial.matches("^[A-Z0-9][A-Z0-9-]{1,39}$"))
            throw invalid("VIOLATION_BOOK_ENTRY_INVALID","assigned officer, active book, matching region and report serial are required");
        Instant at=clock.instant();repo.insertPaperBookEntry(serial,reportSerial,violationNumber,at);
        audit.record("VIOLATION_PAPER_REPORT_ENTERED","ViolationPaperBook",serial,null,
                Map.of("reportSerial",reportSerial,"violationNumber",violationNumber),null);
        events.record("itms.violation.paper-report-entered",violationNumber,Map.of("bookSerial",serial,
                "reportSerial",reportSerial,"violationNumber",violationNumber));
        return new Entry(serial,reportSerial,violationNumber,at);
    }

    @Transactional
    public Book reconcile(String serial,List<String> reportSerials,Caller caller) {
        ViolationService.channelOf(caller);
        Book book=book(serial);require(caller,"violation.book.reconcile",book.region());
        List<String> actual=repo.paperBookReports(serial);
        List<String> declared=reportSerials==null?List.of():reportSerials.stream().sorted().toList();
        if(!"ISSUED".equals(book.status())||!book.officerId().equals(caller.actor().id())||!actual.equals(declared))
            throw invalid("VIOLATION_BOOK_RECONCILIATION_INVALID","only the assigned officer may reconcile and declared report serials must match entries");
        Instant at=clock.instant();repo.finishPaperBook(serial,"RECONCILED",caller.actor().id(),at);
        audit.record("VIOLATION_PAPER_BOOK_RECONCILED","ViolationPaperBook",serial,Map.of("status","ISSUED"),
                Map.of("status","RECONCILED","reportCount",actual.size()),null);
        events.record("itms.violation.paper-book-reconciled",serial,Map.of("bookSerial",serial,"reportCount",actual.size()));
        return book(serial);
    }

    @Transactional
    public Book lost(String serial,String reason,Caller caller) {
        ViolationService.channelOf(caller);
        Book book=book(serial);require(caller,"violation.book.issue",book.region());
        if(!"ISSUED".equals(book.status())||!book.officerId().equals(caller.actor().id())
                ||reason==null||reason.isBlank()||reason.length()>500)
            throw invalid("VIOLATION_BOOK_LOSS_INVALID","assigned officer must give a reason for an issued book");
        repo.finishPaperBook(serial,"LOST",caller.actor().id(),clock.instant());
        audit.record("VIOLATION_PAPER_BOOK_LOST","ViolationPaperBook",serial,Map.of("status","ISSUED"),Map.of("status","LOST"),reason);
        events.record("itms.violation.paper-book-lost",serial,Map.of("bookSerial",serial,"officerId",book.officerId()));
        return book(serial);
    }

    @Transactional(readOnly=true)
    public Book book(String serial) {
        return repo.paperBook(serial).orElseThrow(()->new DomainException(DomainException.Kind.NOT_FOUND,
                "VIOLATION_BOOK_NOT_FOUND","paper violation book does not exist",List.of("VF-013")));
    }

    private static void require(Caller caller,String permission,String region) {
        if(!caller.grants().allowsInRegion(permission,region))
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE,"SCOPE_EXCEEDED",
                    "paper violation book permission is required in this region",List.of("CC-001","VF-013"));
    }
    private static DomainException invalid(String code,String message) {
        return new DomainException(DomainException.Kind.VALIDATION,code,message,List.of("VF-013"));
    }
}

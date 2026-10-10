package gov.itms.violation.api;

import gov.itms.violation.application.ViolationBooks;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Paper violation book workflow (VF-013). */
@RestController
class ViolationBookController {
    private final ViolationBooks books;
    private final Callers callers;
    ViolationBookController(ViolationBooks books,Callers callers){this.books=books;this.callers=callers;}

    record IssueRequest(@NotBlank @Size(max=40) String serial,@NotBlank @Size(max=40) String region,
                        @NotBlank @Size(max=100) String officerId) { }
    record EntryRequest(@NotBlank @Size(max=40) String reportSerial,
                        @NotBlank @Size(max=40) String violationNumber) { }
    record ReconcileRequest(@NotNull @Size(max=500) List<@NotBlank @Size(max=40) String> reportSerials) { }
    record LossRequest(@NotBlank @Size(max=500) String reason) { }

    @PostMapping("/v1/violation-books")
    @PreAuthorize("hasAuthority('violation.book.issue')")
    ResponseEntity<ViolationBooks.Book> issue(@Valid @RequestBody IssueRequest body){
        ViolationBooks.Book book=books.issue(body.serial(),body.region(),body.officerId(),callers.current());
        return ResponseEntity.created(URI.create("/v1/violation-books/"+book.serial())).body(book);
    }

    @PostMapping("/v1/violation-books/{serial}/entries")
    @PreAuthorize("hasAuthority('violation.book.record')")
    ViolationBooks.Entry enter(@PathVariable String serial,@Valid @RequestBody EntryRequest body){
        return books.enter(serial,body.reportSerial(),body.violationNumber(),callers.current());
    }

    @PostMapping("/v1/violation-books/{serial}/reconciliation")
    @PreAuthorize("hasAuthority('violation.book.reconcile')")
    ViolationBooks.Book reconcile(@PathVariable String serial,@Valid @RequestBody ReconcileRequest body){
        return books.reconcile(serial,body.reportSerials(),callers.current());
    }

    @PostMapping("/v1/violation-books/{serial}/losses")
    @PreAuthorize("hasAuthority('violation.book.issue')")
    ViolationBooks.Book lost(@PathVariable String serial,@Valid @RequestBody LossRequest body){
        return books.lost(serial,body.reason(),callers.current());
    }
}

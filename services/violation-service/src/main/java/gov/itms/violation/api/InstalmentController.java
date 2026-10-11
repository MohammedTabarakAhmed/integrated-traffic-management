package gov.itms.violation.api;

import gov.itms.violation.application.InstalmentService;
import gov.itms.violation.domain.InstalmentPlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Authority's own instalment plans (VF-071, VF-073; ADR-062). */
@RestController
class InstalmentController {

    record CreateBody(@NotBlank @Size(max = 60) String fileNumber, @NotBlank @Size(max = 40) String region,
                      @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 30) String> violationNumbers,
                      @Min(1) @Max(60) int months) {
    }

    record InstalmentDto(int seq, LocalDate dueDate, long amountMinor, String itemRef, boolean paid,
                         Instant paidAt) {
    }

    record PlanDto(String number, String fileNumber, String region, String currency, long totalMinor, int months,
                   int graceDays, InstalmentPlan.Status status, String createdBy, Instant createdAt,
                   Instant closedAt, List<String> violationNumbers, List<InstalmentDto> instalments) {
        static PlanDto of(InstalmentPlan p) {
            return new PlanDto(p.number(), p.fileNumber(), p.region(), p.currency(), p.totalMinor(), p.months(),
                    p.graceDays(), p.status(), p.createdBy(), p.createdAt(), p.closedAt(), p.violationNumbers(),
                    p.instalments().stream().map(i -> new InstalmentDto(i.seq(), i.dueDate(), i.amountMinor(),
                            InstalmentPlan.itemRef(p.number(), i.seq()), i.paid(), i.paidAt())).toList());
        }
    }

    record Collection<T>(List<T> items) {
    }

    private final InstalmentService instalments;
    private final Callers callers;

    InstalmentController(InstalmentService instalments, Callers callers) {
        this.instalments = instalments;
        this.callers = callers;
    }

    @PostMapping("/v1/instalment-plans")
    @PreAuthorize("hasAuthority('violation.instalment.create')")
    ResponseEntity<PlanDto> create(@Valid @RequestBody CreateBody body) {
        InstalmentPlan plan = instalments.create(body.fileNumber(), body.region(), body.violationNumbers(),
                body.months(), callers.current());
        return ResponseEntity.created(URI.create("/v1/instalment-plans/" + plan.number())).body(PlanDto.of(plan));
    }

    @GetMapping("/v1/instalment-plans/{number}")
    @PreAuthorize("hasAuthority('violation.read')")
    PlanDto get(@PathVariable String number) {
        return PlanDto.of(instalments.get(number, callers.current()));
    }

    @GetMapping("/v1/instalment-plans")
    @PreAuthorize("hasAuthority('violation.read')")
    Collection<PlanDto> byFile(@RequestParam String fileNumber, @RequestParam(defaultValue = "20") int limit) {
        return new Collection<>(instalments.byFile(fileNumber, limit, callers.current()).stream().map(PlanDto::of)
                .toList());
    }
}

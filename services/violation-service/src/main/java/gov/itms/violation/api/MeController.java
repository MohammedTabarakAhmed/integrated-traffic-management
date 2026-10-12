package gov.itms.violation.api;

import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.violation.api.InstalmentController.PlanDto;
import gov.itms.violation.api.ObjectionController.ObjectionDto;
import gov.itms.violation.api.ObjectionController.SubmitBody;
import gov.itms.violation.api.ViolationController.Collection;
import gov.itms.violation.api.ViolationController.StandingDto;
import gov.itms.violation.api.ViolationController.ViolationDto;
import gov.itms.violation.application.CustomerViolations;
import gov.itms.violation.domain.InstalmentPlan;
import gov.itms.violation.domain.Objection;
import gov.itms.violation.domain.Violation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's own violations and black points (ADR-067 d). {@link CurrentActorProvider#customer()}
 * refuses any token that is not a customer's; the file comes from the customer's token, never from a parameter.
 */
@RestController
class MeController {

    private final CustomerViolations customers;
    private final CurrentActorProvider actors;
    private final Callers callers;

    record OwnPlanBody(@NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 30) String> violationNumbers,
                       @Min(1) @Max(60) int months) {
    }

    MeController(CustomerViolations customers, CurrentActorProvider actors, Callers callers) {
        this.customers = customers;
        this.actors = actors;
        this.callers = callers;
    }

    @GetMapping("/v1/me/violations")
    @PreAuthorize("hasAuthority('customer.violation.read')")
    Collection<ViolationDto> ownViolations(@RequestParam(required = false) Violation.Status status,
                                           @RequestParam(defaultValue = "100") int limit) {
        actors.customer();
        return new Collection<>(customers.violations(status, limit, callers.current()).stream()
                .map(ViolationDto::of).toList());
    }

    /** An objection to one of the customer's own violations (VF-050; partner-state violations go there, XB-005). */
    @PostMapping("/v1/me/violations/{number}/objections")
    @PreAuthorize("hasAuthority('customer.violation.object')")
    ResponseEntity<ObjectionDto> object(@PathVariable String number, @Valid @RequestBody SubmitBody body) {
        actors.customer();
        Objection objection = customers.object(number, body.reasonCode(), body.explanation(),
                body.evidenceIds() == null ? List.of() : body.evidenceIds(), callers.current());
        return ResponseEntity.created(URI.create("/v1/objections/" + objection.id())).body(ObjectionDto.of(objection));
    }

    /** An own instalment plan for some of the customer's own open violations (VF-071). */
    @PostMapping("/v1/me/instalment-plans")
    @PreAuthorize("hasAuthority('customer.instalment.request')")
    ResponseEntity<PlanDto> requestPlan(@Valid @RequestBody OwnPlanBody body) {
        actors.customer();
        InstalmentPlan plan = customers.requestPlan(body.violationNumbers(), body.months(), callers.current());
        return ResponseEntity.created(URI.create("/v1/instalment-plans/" + plan.number())).body(PlanDto.of(plan));
    }

    @GetMapping("/v1/me/instalment-plans")
    @PreAuthorize("hasAuthority('customer.violation.read')")
    InstalmentController.Collection<PlanDto> ownPlans(@RequestParam(defaultValue = "20") int limit) {
        actors.customer();
        return new InstalmentController.Collection<>(customers.plans(limit, callers.current()).stream()
                .map(PlanDto::of).toList());
    }

    @GetMapping("/v1/me/black-point-account")
    @PreAuthorize("hasAuthority('customer.violation.read')")
    StandingDto ownStanding() {
        actors.customer();
        return StandingDto.of(customers.standing(callers.current()));
    }
}

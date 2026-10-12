package gov.itms.violation.api;

import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.violation.api.ViolationController.Collection;
import gov.itms.violation.api.ViolationController.StandingDto;
import gov.itms.violation.api.ViolationController.ViolationDto;
import gov.itms.violation.application.CustomerViolations;
import gov.itms.violation.domain.Violation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/v1/me/black-point-account")
    @PreAuthorize("hasAuthority('customer.violation.read')")
    StandingDto ownStanding() {
        actors.customer();
        return StandingDto.of(customers.standing(callers.current()));
    }
}

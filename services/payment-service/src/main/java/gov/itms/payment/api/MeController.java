package gov.itms.payment.api;

import gov.itms.payment.api.PaymentController.BasketDto;
import gov.itms.payment.api.PaymentController.PayableDto;
import gov.itms.payment.application.CustomerPayments;
import gov.itms.payment.domain.Basket;
import gov.itms.payment.domain.Payable;
import gov.itms.platform.security.CurrentActorProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's own items and baskets (ADR-067 d; CC-047, VF-070). {@link CurrentActorProvider#customer()}
 * refuses any token that is not a customer's; the file comes from the customer's token, never from a parameter.
 */
@RestController
class MeController {

    private final CustomerPayments customers;
    private final CurrentActorProvider actors;
    private final Callers callers;

    MeController(CustomerPayments customers, CurrentActorProvider actors, Callers callers) {
        this.customers = customers;
        this.actors = actors;
        this.callers = callers;
    }

    /** An item with its itemised lines (CC-047) and whether it is paused under objection (VF-052). */
    record OwnPayableDto(PayableDto payable, boolean paused) {
    }

    record OwnPayables(List<OwnPayableDto> items) {
    }

    /** The items to pay; none means every open item that is not paused (VF-070). */
    record OwnBasketRequest(@Size(max = 100) List<@NotNull UUID> payableIds) {
    }

    @GetMapping("/v1/me/payables")
    @PreAuthorize("hasAuthority('customer.payment.make')")
    OwnPayables ownPayables(@RequestParam(required = false) Payable.Status status,
                            @RequestParam(defaultValue = "100") int limit) {
        actors.customer();
        return new OwnPayables(customers.items(status, limit, callers.current()).stream()
                .map(i -> new OwnPayableDto(PayableDto.of(i.payable()), i.paused())).toList());
    }

    @PostMapping("/v1/me/baskets")
    @PreAuthorize("hasAuthority('customer.payment.make')")
    ResponseEntity<BasketDto> ownBasket(@Valid @RequestBody OwnBasketRequest body) {
        actors.customer();
        Basket b = customers.basket(body.payableIds(), callers.current());
        return ResponseEntity.created(URI.create("/v1/baskets/" + b.id())).body(BasketDto.of(b));
    }
}

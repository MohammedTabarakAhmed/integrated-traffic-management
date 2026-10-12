package gov.itms.transaction.api;

import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import gov.itms.transaction.api.ApiModels.Collection;
import gov.itms.transaction.api.ApiModels.StatusChangeDto;
import gov.itms.transaction.api.ApiModels.TransactionDto;
import gov.itms.transaction.api.ApiModels.TransactionPageDto;
import gov.itms.transaction.application.Caller;
import gov.itms.transaction.application.CustomerTransactions;
import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.TransactionStatus;
import java.util.Arrays;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer tracks their own transactions by reference (CC-025, ADR-067 d).
 * {@link CurrentActorProvider#customer()} refuses any token that is not a customer's; the applicant file comes from
 * the customer's token, never from a parameter.
 */
@RestController
@RequestMapping("/v1/me/transactions")
class MeController {

    private final CustomerTransactions customers;
    private final CurrentActorProvider actors;

    MeController(CustomerTransactions customers, CurrentActorProvider actors) {
        this.customers = customers;
        this.actors = actors;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer.transaction.read')")
    TransactionPageDto list(@RequestParam(required = false) TransactionStatus status,
                            @RequestParam(required = false) Integer limit,
                            @RequestParam(required = false) String cursor) {
        return TransactionPageDto.of(customers.list(status, cursor, limit, caller()));
    }

    @GetMapping("/{reference}")
    @PreAuthorize("hasAuthority('customer.transaction.read')")
    TransactionDto get(@PathVariable String reference) {
        return TransactionDto.of(customers.get(reference, caller()));
    }

    @GetMapping("/{reference}/status-history")
    @PreAuthorize("hasAuthority('customer.transaction.read')")
    Collection<StatusChangeDto> history(@PathVariable String reference) {
        return new Collection<>(customers.history(reference, caller()).stream().map(StatusChangeDto::of).toList());
    }

    private Caller caller() {
        actors.customer();
        CurrentActor current = actors.current();
        Authorisation authorisation = actors.authorisation();
        Channel channel = Arrays.stream(Channel.values()).filter(c -> c.name().equals(current.channel())).findFirst()
                .orElse(null);
        return new Caller(new Actor(current.userId(), current.username()), channel,
                (permission, region, module) -> authorisation.allows(permission,
                        new ScopeTarget(region, null, module)));
    }
}

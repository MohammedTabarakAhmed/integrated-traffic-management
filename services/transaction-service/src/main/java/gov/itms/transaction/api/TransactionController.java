package gov.itms.transaction.api;

import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import gov.itms.transaction.api.ApiModels.CancelTransactionRequest;
import gov.itms.transaction.api.ApiModels.Collection;
import gov.itms.transaction.api.ApiModels.CreateTransactionRequest;
import gov.itms.transaction.api.ApiModels.StatusChangeDto;
import gov.itms.transaction.api.ApiModels.TransactionDto;
import gov.itms.transaction.application.Caller;
import gov.itms.transaction.application.TransactionService;
import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.Transaction;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transactions of {@code transaction-service.v1.yaml}. {@code @PreAuthorize} checks that the caller holds the
 * permission somewhere; {@link TransactionService} checks that it reaches the transaction's region and module (CC-001).
 */
@RestController
@RequestMapping("/v1/transactions")
class TransactionController {

    private final TransactionService service;
    private final CurrentActorProvider actors;

    TransactionController(TransactionService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('transaction.create')")
    ResponseEntity<TransactionDto> create(@Valid @RequestBody CreateTransactionRequest body) {
        Transaction created = service.create(body.toDomain(), caller());
        return ResponseEntity.created(URI.create("/v1/transactions/" + created.reference()))
                .body(TransactionDto.of(created));
    }

    @GetMapping("/{reference}")
    @PreAuthorize("hasAuthority('transaction.read')")
    TransactionDto get(@PathVariable String reference) {
        return TransactionDto.of(service.get(reference, caller()));
    }

    @GetMapping("/{reference}/status-history")
    @PreAuthorize("hasAuthority('transaction.read')")
    Collection<StatusChangeDto> history(@PathVariable String reference) {
        return new Collection<>(service.history(reference, caller()).stream().map(StatusChangeDto::of).toList());
    }

    @PostMapping("/{reference}/submissions")
    @PreAuthorize("hasAuthority('transaction.create')")
    TransactionDto submit(@PathVariable String reference) {
        return TransactionDto.of(service.submit(reference, caller()));
    }

    @PostMapping("/{reference}/cancellations")
    @PreAuthorize("hasAuthority('transaction.cancel')")
    TransactionDto cancel(@PathVariable String reference, @Valid @RequestBody CancelTransactionRequest body) {
        return TransactionDto.of(service.cancel(reference, body.reason(), caller()));
    }

    /** The channel comes from {@code X-Channel}; anything else than a channel of {@link Channel} counts as none. */
    private Caller caller() {
        CurrentActor current = actors.current();
        Authorisation authorisation = actors.authorisation();
        Channel channel = Arrays.stream(Channel.values()).filter(c -> c.name().equals(current.channel())).findFirst()
                .orElse(null);
        return new Caller(new Actor(current.userId(), current.username()), channel,
                (permission, region, module) -> authorisation.allows(permission,
                        new ScopeTarget(region, null, module)));
    }
}

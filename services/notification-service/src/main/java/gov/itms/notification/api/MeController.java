package gov.itms.notification.api;

import gov.itms.notification.api.ApiModels.Collection;
import gov.itms.notification.api.ApiModels.NotificationDto;
import gov.itms.notification.api.ApiModels.RecipientDto;
import gov.itms.notification.api.ApiModels.RecipientRequest;
import gov.itms.notification.application.CustomerNotifications;
import gov.itms.platform.security.CurrentActorProvider;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's own messages and channel preferences (CC-073, CH-002; ADR-067 d).
 * {@link CurrentActorProvider#customer()} refuses any token that is not a customer's; the recipient is the customer's
 * traffic file, from their token, never a parameter.
 */
@RestController
class MeController {

    private final CustomerNotifications customers;
    private final CurrentActorProvider actors;
    private final Callers callers;

    MeController(CustomerNotifications customers, CurrentActorProvider actors, Callers callers) {
        this.customers = customers;
        this.actors = actors;
        this.callers = callers;
    }

    @GetMapping("/v1/me/inbox")
    @PreAuthorize("hasAuthority('customer.notification.read')")
    Collection<NotificationDto> inbox(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                      @RequestParam(defaultValue = "50") int limit) {
        actors.customer();
        return new Collection<>(customers.inbox(unreadOnly, limit, callers.current()).stream()
                .map(NotificationDto::of).toList());
    }

    @PostMapping("/v1/me/inbox/{id}/reads")
    @PreAuthorize("hasAuthority('customer.notification.read')")
    NotificationDto markRead(@PathVariable UUID id) {
        actors.customer();
        return NotificationDto.of(customers.markRead(id, callers.current()));
    }

    @GetMapping("/v1/me/notification-preferences")
    @PreAuthorize("hasAuthority('customer.notification.read')")
    RecipientDto preferences() {
        actors.customer();
        return RecipientDto.of(customers.preferences(callers.current()));
    }

    /** A profile change: configured as a SEC-002 step-up path, so it needs a one-time-password sign-in. */
    @PostMapping("/v1/me/notification-preferences")
    @PreAuthorize("hasAuthority('customer.notification.preferences')")
    RecipientDto savePreferences(@Valid @RequestBody RecipientRequest body) {
        actors.customer();
        return RecipientDto.of(customers.savePreferences(body::toDomain, callers.current()));
    }
}

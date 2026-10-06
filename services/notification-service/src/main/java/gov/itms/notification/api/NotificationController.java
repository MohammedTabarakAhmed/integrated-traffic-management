package gov.itms.notification.api;

import gov.itms.notification.api.ApiModels.Collection;
import gov.itms.notification.api.ApiModels.ExpiryWatchDto;
import gov.itms.notification.api.ApiModels.NotificationDto;
import gov.itms.notification.api.ApiModels.PublishTemplateRequest;
import gov.itms.notification.api.ApiModels.ReceiptRequest;
import gov.itms.notification.api.ApiModels.RecipientDto;
import gov.itms.notification.api.ApiModels.RecipientRequest;
import gov.itms.notification.api.ApiModels.SendRequest;
import gov.itms.notification.api.ApiModels.SubscriptionDto;
import gov.itms.notification.api.ApiModels.TemplateDto;
import gov.itms.notification.application.NotificationService;
import gov.itms.notification.domain.Template;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Templates, notifications, inbox, recipients, subscriptions, expiries and receipts (CC-070..CC-074). */
@RestController
class NotificationController {

    private final NotificationService notifications;
    private final Callers callers;

    NotificationController(NotificationService notifications, Callers callers) {
        this.notifications = notifications;
        this.callers = callers;
    }

    @PostMapping("/v1/notification-templates")
    @PreAuthorize("hasAuthority('notification.template.manage')")
    ResponseEntity<TemplateDto> publish(@Valid @RequestBody PublishTemplateRequest body) {
        Template t = notifications.publishTemplate(body.code(), body.category(), body.mandatory(), body.toDomain(),
                body.reason(), callers.current());
        return ResponseEntity.created(URI.create("/v1/notification-templates/" + t.code())).body(TemplateDto.of(t));
    }

    @GetMapping("/v1/notification-templates")
    @PreAuthorize("hasAuthority('notification.template.read') or hasAuthority('notification.template.manage')")
    Collection<TemplateDto> templates() {
        return new Collection<>(notifications.templates(callers.current()).stream().map(TemplateDto::of).toList());
    }

    @GetMapping("/v1/notification-templates/{code}")
    @PreAuthorize("hasAuthority('notification.template.read') or hasAuthority('notification.template.manage')")
    TemplateDto template(@PathVariable String code) {
        return TemplateDto.of(notifications.template(code, callers.current()));
    }

    @PostMapping("/v1/notifications")
    @PreAuthorize("hasAuthority('notification.send')")
    ResponseEntity<Collection<NotificationDto>> send(@Valid @RequestBody SendRequest body) {
        return ResponseEntity.status(201).body(new Collection<>(notifications.send(body.templateCode(),
                body.recipientId(), body.data(), body.reference(), callers.current()).stream()
                .map(NotificationDto::of).toList()));
    }

    @GetMapping("/v1/notifications")
    @PreAuthorize("hasAuthority('notification.read')")
    Collection<NotificationDto> byRecipient(@RequestParam String recipientId,
                                            @RequestParam(required = false) String reference,
                                            @RequestParam(defaultValue = "50") int limit) {
        return new Collection<>(notifications.byRecipient(recipientId, reference, limit, callers.current()).stream()
                .map(NotificationDto::of).toList());
    }

    @GetMapping("/v1/notifications/{id}")
    @PreAuthorize("isAuthenticated()")
    NotificationDto get(@PathVariable UUID id) {
        return NotificationDto.of(notifications.get(id, callers.current()));
    }

    @GetMapping("/v1/inbox")
    @PreAuthorize("isAuthenticated()")
    Collection<NotificationDto> inbox(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                      @RequestParam(defaultValue = "50") int limit) {
        return new Collection<>(notifications.inbox(unreadOnly, limit, callers.current()).stream()
                .map(NotificationDto::of).toList());
    }

    @PostMapping("/v1/inbox/{id}/reads")
    @PreAuthorize("isAuthenticated()")
    NotificationDto markRead(@PathVariable UUID id) {
        return NotificationDto.of(notifications.markRead(id, callers.current()));
    }

    @PostMapping("/v1/recipients/{recipientId}")
    @PreAuthorize("isAuthenticated()")
    RecipientDto saveRecipient(@PathVariable String recipientId, @Valid @RequestBody RecipientRequest body) {
        return RecipientDto.of(notifications.saveRecipient(body.toDomain(recipientId), callers.current()));
    }

    @GetMapping("/v1/recipients/{recipientId}")
    @PreAuthorize("isAuthenticated()")
    RecipientDto recipient(@PathVariable String recipientId) {
        return RecipientDto.of(notifications.recipient(recipientId, callers.current()));
    }

    @PostMapping("/v1/event-subscriptions")
    @PreAuthorize("hasAuthority('notification.subscription.manage')")
    SubscriptionDto subscribe(@Valid @RequestBody SubscriptionDto body) {
        return SubscriptionDto.of(notifications.saveSubscription(body.toDomain(), callers.current()));
    }

    @GetMapping("/v1/event-subscriptions")
    @PreAuthorize("hasAuthority('notification.subscription.manage') or hasAuthority('notification.template.read')")
    Collection<SubscriptionDto> subscriptions() {
        return new Collection<>(notifications.subscriptions(callers.current()).stream().map(SubscriptionDto::of)
                .toList());
    }

    @PostMapping("/v1/expiry-watches")
    @PreAuthorize("hasAuthority('notification.expiry.register')")
    ExpiryWatchDto registerExpiry(@Valid @RequestBody ExpiryWatchDto body) {
        return ExpiryWatchDto.of(notifications.registerExpiry(body.toDomain(), callers.current()));
    }

    @PostMapping("/v1/delivery-receipts")
    @PreAuthorize("hasAuthority('notification.receipt')")
    NotificationDto receipt(@Valid @RequestBody ReceiptRequest body) {
        return NotificationDto.of(notifications.receipt(body.messageId(), body.delivered(), body.error(), body.at(),
                callers.current()));
    }
}

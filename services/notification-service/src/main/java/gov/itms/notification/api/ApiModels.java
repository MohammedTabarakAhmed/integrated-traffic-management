package gov.itms.notification.api;

import gov.itms.notification.domain.DeliveryChannel;
import gov.itms.notification.domain.Language;
import gov.itms.notification.domain.Notification;
import gov.itms.notification.domain.Recipient;
import gov.itms.notification.domain.Subscriptions;
import gov.itms.notification.domain.Template;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Request/response shapes of {@code notification-service.v1.yaml}. */
final class ApiModels {

    private ApiModels() {
    }

    record ContentDto(@Size(max = 200) String subjectNl, @Size(max = 200) String subjectEn,
                      @NotBlank @Size(max = 10000) String bodyNl, @NotBlank @Size(max = 10000) String bodyEn) {
    }

    record PublishTemplateRequest(@NotBlank String code, @NotBlank String category, boolean mandatory,
                                  @NotNull @Size(min = 1, max = 4) Map<DeliveryChannel, @NotNull @Valid ContentDto> contents,
                                  @NotBlank @Size(min = 3, max = 500) String reason) {
        Map<DeliveryChannel, Template.Content> toDomain() {
            Map<DeliveryChannel, Template.Content> m = new EnumMap<>(DeliveryChannel.class);
            contents.forEach((c, d) -> m.put(c, new Template.Content(d.subjectNl(), d.subjectEn(), d.bodyNl(),
                    d.bodyEn())));
            return m;
        }
    }

    record TemplateDto(String code, int versionNo, String category, boolean mandatory,
                       Map<DeliveryChannel, ContentDto> contents, String publishedBy, Instant publishedAt) {
        static TemplateDto of(Template t) {
            Map<DeliveryChannel, ContentDto> m = new EnumMap<>(DeliveryChannel.class);
            t.contents().forEach((c, d) -> m.put(c, new ContentDto(d.subjectNl(), d.subjectEn(), d.bodyNl(),
                    d.bodyEn())));
            return new TemplateDto(t.code(), t.versionNo(), t.category(), t.mandatory(), m, t.publishedBy().id(),
                    t.publishedAt());
        }
    }

    record SendRequest(@NotBlank String templateCode, @NotBlank @Size(max = 100) String recipientId,
                       @Size(max = 50) Map<String, @Size(max = 500) String> data, @Size(max = 60) String reference) {
    }

    /** A delivery; the destination is masked (SEC-014). The body is shown to the recipient and to staff only. */
    record NotificationDto(UUID id, String recipientId, String templateCode, int templateVersion, String category,
                           boolean mandatory, DeliveryChannel channel, String destination, Language language,
                           String subject, String body, String reference, Instant createdAt,
                           Notification.Status status, int attempts, Instant nextAttemptAt, String lastError,
                           Instant sentAt, Instant deliveredAt, Instant readAt) {
        static NotificationDto of(Notification n) {
            return new NotificationDto(n.id(), n.recipientId(), n.templateCode(), n.templateVersion(), n.category(),
                    n.mandatory(), n.channel(), Recipient.mask(n.channel(), n.destination()), n.language(),
                    n.subject(), n.body(), n.reference(), n.createdAt(), n.status(), n.attempts(),
                    n.nextAttemptAt(), n.lastError(), n.sentAt(), n.deliveredAt(), n.readAt());
        }
    }

    record RecipientRequest(Language language, @Size(max = 16) String phone, @Size(max = 254) String email,
                            @Size(max = 500) String pushToken,
                            @Size(max = 50) Map<String, @NotNull Set<DeliveryChannel>> preferences) {
        Recipient toDomain(String id) {
            return new Recipient(id, language, phone, email, pushToken, preferences);
        }
    }

    record RecipientDto(String id, Language language, String phone, String email, boolean push,
                        Map<String, Set<DeliveryChannel>> preferences) {
        static RecipientDto of(Recipient r) {
            return new RecipientDto(r.id(), r.language(), Recipient.mask(DeliveryChannel.SMS, r.phone()),
                    Recipient.mask(DeliveryChannel.EMAIL, r.email()), r.pushToken() != null, r.preferences());
        }
    }

    record SubscriptionDto(@NotBlank String eventType, @NotBlank String templateCode, @NotBlank String recipientField,
                           String referenceField, @NotNull Boolean active) {
        Subscriptions.EventSubscription toDomain() {
            return new Subscriptions.EventSubscription(eventType, templateCode, recipientField, referenceField,
                    active);
        }

        static SubscriptionDto of(Subscriptions.EventSubscription s) {
            return new SubscriptionDto(s.eventType(), s.templateCode(), s.recipientField(), s.referenceField(),
                    s.active());
        }
    }

    record ExpiryWatchDto(@NotNull Subscriptions.SubjectType subjectType, @NotBlank String subjectId,
                          @NotBlank String recipientId, @NotNull LocalDate expiryDate,
                          @Size(max = 60) String reference) {
        Subscriptions.ExpiryWatch toDomain() {
            return new Subscriptions.ExpiryWatch(subjectType, subjectId, recipientId, expiryDate, reference);
        }

        static ExpiryWatchDto of(Subscriptions.ExpiryWatch w) {
            return new ExpiryWatchDto(w.subjectType(), w.subjectId(), w.recipientId(), w.expiryDate(),
                    w.reference());
        }
    }

    record ReceiptRequest(@NotBlank @Size(max = 200) String messageId, @NotNull Boolean delivered,
                          @Size(max = 500) String error, Instant at) {
    }

    record Collection<T>(List<T> items) {
    }
}

package gov.itms.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.notification.domain.Actor;
import gov.itms.notification.domain.Channel;
import gov.itms.notification.domain.DeliveryChannel;
import gov.itms.notification.domain.DomainException;
import gov.itms.notification.domain.Language;
import gov.itms.notification.domain.Notification;
import gov.itms.notification.domain.Recipient;
import gov.itms.notification.domain.Subscriptions;
import gov.itms.notification.domain.Template;
import gov.itms.notification.domain.TemplateTest;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.ProcessedEvents;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-070")
class NotificationServiceTest {

    private static final Instant NOW = TemplateTest.NOW;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    private final Repo repo = new Repo();
    private final List<String> audit = new ArrayList<>();
    private final List<String> sentToGateway = new ArrayList<>();
    private boolean gatewayDown;
    private final Set<String> seenEvents = new HashSet<>();
    private final NotificationService service = new NotificationService(repo,
            (channel, destination, subject, body, notificationId) -> {
                if (gatewayDown) {
                    throw new ChannelGateway.GatewayException("connection refused", null);
                }
                sentToGateway.add(channel + " " + destination + " " + body);
                return "gw-" + notificationId;
            },
            new AuditRecorder() {
                @Override
                public void record(String action, String entityType, String entityId, Object oldValue,
                                   Object newValue, String reason) {
                    audit.add(action + " " + entityId);
                }

                @Override
                public void recordAs(CurrentActor actor, String action, String entityType, String entityId,
                                     Object oldValue, Object newValue, String reason) {
                    record(action, entityType, entityId, oldValue, newValue, reason);
                }
            },
            new ProcessedEvents(null, null) {
                @Override
                public boolean firstDelivery(String consumer, String eventId) {
                    return seenEvents.add(consumer + "/" + eventId);
                }
            },
            new NotificationService.Settings(2, Duration.ofSeconds(30), List.of(30, 7, 1), List.of(
                    new NotificationService.ExpiryEvent("itms.licence.issued", Subscriptions.SubjectType.LICENCE,
                            "licenceNumber", "trafficFileNumber", "expiresOn"),
                    new NotificationService.ExpiryEvent("itms.permit.status-changed",
                            Subscriptions.SubjectType.PERMIT, "number", "trafficFileNumber", "expiresOn"))),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static Caller caller(String id, String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor(id, id), Channel.BACKOFFICE, new Caller.Grants() {
            @Override
            public boolean allowsInRegion(String permission, String region) {
                return held.contains(permission);
            }

            @Override
            public boolean holds(String permission) {
                return held.contains(permission);
            }
        });
    }

    private static final Caller ADMIN = caller("u-admin", NotificationService.TEMPLATE_MANAGE,
            NotificationService.SUBSCRIPTION_MANAGE);
    private static final Caller SENDER = caller("svc-vehicle", NotificationService.SEND,
            NotificationService.EXPIRY_REGISTER);
    private static final Caller GATEWAY = caller("svc-gateway", NotificationService.RECEIPT);

    private void templates() {
        Template t = TemplateTest.template("VL_RENEWED", "VEHICLE", false);
        service.publishTemplate("VL_RENEWED", "VEHICLE", false, t.contents(), "first version", ADMIN);
        service.publishTemplate("EXPIRY_REMINDER_LICENCE", "LICENCE", false, Map.of(DeliveryChannel.SMS,
                new Template.Content(null, null, "تنتهي {{expiryDate}}", "Expires {{expiryDate}} in {{daysLeft}} days")),
                "first version", ADMIN);
        repo.saveRecipient(new Recipient("c-1", Language.EN, "+97150123456", null, null, Map.of()));
    }

    @Test
    @Tag("CC-073")
    @DisplayName("CC-070, CC-073: a send renders per reachable channel; the inbox is delivered, SMS waits for dispatch")
    void sendAndDispatch() {
        templates();
        List<Notification> sent = service.send("VL_RENEWED", "c-1", Map.of("plate", "A 1"), "REF-1", SENDER);
        assertThat(sent).extracting(Notification::channel).containsExactlyInAnyOrder(DeliveryChannel.SMS,
                DeliveryChannel.INBOX);
        assertThat(audit).contains("NOTIFICATION_REQUESTED c-1");

        assertThat(service.dispatch(50)).isEqualTo(1);
        assertThat(sentToGateway).containsExactly("SMS +97150123456 Plate A 1 renewed");
        Notification sms = sent.stream().filter(n -> n.channel() == DeliveryChannel.SMS).findFirst().orElseThrow();
        assertThat(sms.status()).isEqualTo(Notification.Status.SENT);

        Notification delivered = service.receipt("gw-" + sms.id(), true, null, null, GATEWAY);
        assertThat(delivered.status()).isEqualTo(Notification.Status.DELIVERED);
        assertThat(audit).contains("NOTIFICATION_DELIVERED " + sms.id());
        assertThatThrownBy(() -> service.receipt("gw-" + sms.id(), true, null, null, GATEWAY))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("NOTIFICATION_NOT_SENT");
        assertThatThrownBy(() -> service.receipt("gw-unknown", true, null, null, GATEWAY))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("NOTIFICATION_NOT_FOUND");
        assertThatThrownBy(() -> service.send("NO_SUCH", "c-1", Map.of(), null, SENDER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("TEMPLATE_NOT_FOUND");
    }

    @Test
    @Tag("CC-074")
    @DisplayName("CC-074: a gateway failure is retried later and FAILED after the configured attempts")
    void gatewayFailure() {
        templates();
        service.send("VL_RENEWED", "c-1", Map.of("plate", "A 1"), null, SENDER);
        gatewayDown = true;
        assertThat(service.dispatch(50)).isEqualTo(1);
        Notification sms = repo.notifications.values().stream().filter(n -> n.channel() == DeliveryChannel.SMS)
                .findFirst().orElseThrow();
        assertThat(sms.status()).isEqualTo(Notification.Status.QUEUED);
        assertThat(service.dispatch(50)).as("not due again before the retry delay").isZero();
        repo.ignoreDueTime = true;
        service.dispatch(50);
        assertThat(sms.status()).isEqualTo(Notification.Status.FAILED);
        assertThat(sms.lastError()).contains("connection refused");
    }

    @Test
    @Tag("CC-071")
    @DisplayName("CC-071: an event triggers active subscriptions once; inactive ones and events without a recipient do not")
    void eventsOnce() {
        templates();
        service.saveSubscription(new Subscriptions.EventSubscription("itms.vehicle.registration-renewed",
                "VL_RENEWED", "ownerId", "registrationNo", true), ADMIN);
        service.saveSubscription(new Subscriptions.EventSubscription("itms.vehicle.registration-renewed",
                "EXPIRY_REMINDER_LICENCE", "ownerId", null, false), ADMIN);
        Map<String, Object> data = Map.of("ownerId", "c-1", "registrationNo", "REG-9", "plate", "A 1",
                "nested", Map.of("ignored", true));

        assertThat(service.onEvent("evt-1", "itms.vehicle.registration-renewed", data)).isEqualTo(2);
        assertThat(service.onEvent("evt-1", "itms.vehicle.registration-renewed", data))
                .as("a redelivered event does nothing").isZero();
        assertThat(repo.notifications.values()).extracting(Notification::reference).containsOnly("REG-9");
        assertThat(repo.notifications.values()).extracting(Notification::sourceEventId).containsOnly("evt-1");
        assertThat(service.onEvent("evt-2", "itms.vehicle.registration-renewed", Map.of("plate", "A 1")))
                .as("no recipient in the event").isZero();
        assertThat(service.onEvent("evt-3", "itms.vehicle.registration-renewed", Map.of("ownerId", "c-1")))
                .as("template data missing: logged, not thrown").isZero();
        assertThatThrownBy(() -> service.saveSubscription(new Subscriptions.EventSubscription("itms.a.b",
                "NO_TEMPLATE", "ownerId", null, true), ADMIN)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("TEMPLATE_NOT_FOUND");
    }

    @Test
    @Tag("CC-072")
    @DisplayName("CC-072: each reminder offset goes out once for an expiry")
    void remindersOncePerOffset() {
        templates();
        service.registerExpiry(new Subscriptions.ExpiryWatch(Subscriptions.SubjectType.LICENCE, "DL-1", "c-1",
                TODAY.plusDays(7), "DL-1"), SENDER);
        service.registerExpiry(new Subscriptions.ExpiryWatch(Subscriptions.SubjectType.LICENCE, "DL-2", "c-1",
                TODAY.plusDays(8), null), SENDER);
        assertThat(service.sendDueReminders(100)).isEqualTo(1);
        assertThat(service.sendDueReminders(100)).as("already sent at this offset").isZero();
        Notification reminder = repo.notifications.values().iterator().next();
        assertThat(reminder.body()).isEqualTo("Expires " + TODAY.plusDays(7) + " in 7 days");
        assertThat(audit).contains("EXPIRY_REMINDER_SENT LICENCE:DL-1");
    }

    @Test
    @Tag("CC-072")
    @DisplayName("CC-072: an issue event of the owning service registers and moves the expiry; others register nothing")
    void expiriesFromEvents() {
        templates();
        service.onEvent("evt-l1", "itms.licence.issued", Map.of("licenceNumber", "DL-9", "trafficFileNumber", "c-1",
                "expiresOn", TODAY.plusDays(30).toString(), "classes", List.of()));
        assertThat(repo.watches).containsExactly(new Subscriptions.ExpiryWatch(Subscriptions.SubjectType.LICENCE,
                "DL-9", "c-1", TODAY.plusDays(30), "DL-9"));
        assertThat(audit).contains("EXPIRY_REGISTERED LICENCE:DL-9");
        service.onEvent("evt-p1", "itms.permit.status-changed", Map.of("number", "TP-1", "status", "SUSPENDED"));
        service.onEvent("evt-p2", "itms.permit.status-changed", Map.of("number", "TP-1", "status", "ISSUED",
                "trafficFileNumber", "c-1", "expiresOn", "not-a-date"));
        assertThat(repo.watches).as("no expiry, or no usable one: nothing registered").hasSize(1);
        service.onEvent("evt-l1", "itms.licence.issued", Map.of("licenceNumber", "DL-9", "trafficFileNumber", "c-1",
                "expiresOn", TODAY.plusDays(400).toString()));
        assertThat(repo.watches.getFirst().expiryDate()).as("redelivery ignored").isEqualTo(TODAY.plusDays(30));
        service.onEvent("evt-l2", "itms.licence.issued", Map.of("licenceNumber", "DL-9", "trafficFileNumber", "c-1",
                "expiresOn", TODAY.plusDays(7).toString()));
        assertThat(repo.watches).singleElement().extracting(Subscriptions.ExpiryWatch::expiryDate)
                .isEqualTo(TODAY.plusDays(7));
        assertThat(service.sendDueReminders(100)).isEqualTo(1);
    }

    @Test
    @Tag("CC-001")
    @Tag("CC-073")
    @DisplayName("CC-001, CC-073: customers manage their own contacts and inbox; others need permissions")
    void ownScope() {
        templates();
        Caller customer = caller("c-1");
        service.saveRecipient(new Recipient("c-1", Language.EN, "+97150123456", "a.person@example.org", null,
                Map.of("VEHICLE", Set.of(DeliveryChannel.EMAIL))), customer);
        assertThatThrownBy(() -> service.saveRecipient(Recipient.unknown("c-2"), customer))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.recipient("c-2", customer)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("SCOPE_EXCEEDED");

        List<Notification> sent = service.send("VL_RENEWED", "c-1", Map.of("plate", "A 1"), null, SENDER);
        assertThat(sent).extracting(Notification::channel).containsExactlyInAnyOrder(DeliveryChannel.EMAIL);
        service.send("VL_RENEWED", "c-1", Map.of("plate", "A 1"), null, SENDER);
        Notification email = sent.getFirst();
        assertThat(service.get(email.id(), customer).id()).isEqualTo(email.id());
        assertThatThrownBy(() -> service.get(email.id(), caller("c-2"))).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.markRead(email.id(), customer)).as("not an inbox message")
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("NOTIFICATION_NOT_FOUND");
        assertThatThrownBy(() -> service.byRecipient("c-1", null, 10, customer))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThat(service.byRecipient("c-1", null, 10, caller("u-support", NotificationService.READ))).hasSize(2);
        assertThat(audit).contains("NOTIFICATIONS_LISTED c-1");
    }

    @Test
    @DisplayName("CC-070: publishing needs the permission and a reason; versions count up")
    void publishing() {
        Template t = TemplateTest.template("VL_RENEWED", "VEHICLE", false);
        assertThat(service.publishTemplate("VL_RENEWED", "VEHICLE", false, t.contents(), "first version", ADMIN)
                .versionNo()).isEqualTo(1);
        assertThat(service.publishTemplate("VL_RENEWED", "VEHICLE", false, t.contents(), "second version", ADMIN)
                .versionNo()).isEqualTo(2);
        assertThatThrownBy(() -> service.publishTemplate("VL_RENEWED", "VEHICLE", false, t.contents(), "second",
                SENDER)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.publishTemplate("VL_RENEWED", "VEHICLE", false, t.contents(), "x", ADMIN))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("REASON_INVALID");
    }

    private static final class Repo implements NotificationRepository {
        final Map<String, List<Template>> templates = new HashMap<>();
        final Map<String, Recipient> recipients = new HashMap<>();
        final Map<UUID, Notification> notifications = new LinkedHashMap<>();
        final List<Subscriptions.EventSubscription> subscriptions = new ArrayList<>();
        final List<Subscriptions.ExpiryWatch> watches = new ArrayList<>();
        final Set<String> remindersSent = new HashSet<>();
        boolean ignoreDueTime;

        @Override
        public void lockTemplate(String code) {
        }

        @Override
        public int nextTemplateVersion(String code) {
            return templates.getOrDefault(code, List.of()).size() + 1;
        }

        @Override
        public void insertTemplate(Template template) {
            templates.computeIfAbsent(template.code(), c -> new ArrayList<>()).add(template);
        }

        @Override
        public Optional<Template> latestTemplate(String code) {
            List<Template> all = templates.getOrDefault(code, List.of());
            return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
        }

        @Override
        public Optional<Template> findTemplate(String code, int versionNo) {
            return templates.getOrDefault(code, List.of()).stream().filter(t -> t.versionNo() == versionNo)
                    .findFirst();
        }

        @Override
        public List<Template> latestTemplates() {
            return templates.values().stream().map(List::getLast).toList();
        }

        @Override
        public Optional<Recipient> findRecipient(String id) {
            return Optional.ofNullable(recipients.get(id));
        }

        @Override
        public void saveRecipient(Recipient recipient) {
            recipients.put(recipient.id(), recipient);
        }

        @Override
        public void insertNotification(Notification notification) {
            notifications.put(notification.id(), notification);
        }

        @Override
        public Optional<Notification> findNotification(UUID id) {
            return Optional.ofNullable(notifications.get(id));
        }

        @Override
        public Optional<Notification> findByProviderId(String providerMessageId) {
            return notifications.values().stream().filter(n -> providerMessageId.equals(n.providerMessageId()))
                    .findFirst();
        }

        @Override
        public void updateNotification(Notification notification) {
        }

        @Override
        public List<Notification> byRecipient(String recipientId, String reference, int limit) {
            return notifications.values().stream().filter(n -> n.recipientId().equals(recipientId))
                    .filter(n -> reference == null || reference.equals(n.reference())).limit(limit).toList();
        }

        @Override
        public List<Notification> inbox(String recipientId, boolean unreadOnly, int limit) {
            return notifications.values().stream().filter(n -> n.recipientId().equals(recipientId)
                    && n.channel() == DeliveryChannel.INBOX && (!unreadOnly || n.readAt() == null)).limit(limit)
                    .toList();
        }

        @Override
        public List<Notification> dueForSending(Instant now, int limit) {
            return notifications.values().stream().filter(n -> n.status() == Notification.Status.QUEUED
                    && (ignoreDueTime || !n.nextAttemptAt().isAfter(now)))
                    .sorted(Comparator.comparing(Notification::createdAt)).limit(limit).toList();
        }

        @Override
        public void saveSubscription(Subscriptions.EventSubscription subscription) {
            subscriptions.removeIf(s -> s.eventType().equals(subscription.eventType())
                    && s.templateCode().equals(subscription.templateCode()));
            subscriptions.add(subscription);
        }

        @Override
        public List<Subscriptions.EventSubscription> subscriptions(String eventType) {
            return subscriptions.stream().filter(s -> s.eventType().equals(eventType)).toList();
        }

        @Override
        public List<Subscriptions.EventSubscription> allSubscriptions() {
            return List.copyOf(subscriptions);
        }

        @Override
        public void saveExpiryWatch(Subscriptions.ExpiryWatch watch) {
            watches.removeIf(w -> w.subjectType() == watch.subjectType() && w.subjectId().equals(watch.subjectId()));
            watches.add(watch);
        }

        @Override
        public List<DueReminder> remindersDue(LocalDate today, List<Integer> offsets, int limit) {
            List<DueReminder> due = new ArrayList<>();
            for (Subscriptions.ExpiryWatch w : watches) {
                for (int offset : offsets) {
                    if (w.expiryDate().minusDays(offset).equals(today)) {
                        due.add(new DueReminder(w, offset));
                    }
                }
            }
            return due;
        }

        @Override
        public boolean markReminderSent(Subscriptions.SubjectType type, String subjectId, LocalDate expiryDate,
                                        int offsetDays) {
            return remindersSent.add(type + "/" + subjectId + "/" + expiryDate + "/" + offsetDays);
        }
    }
}

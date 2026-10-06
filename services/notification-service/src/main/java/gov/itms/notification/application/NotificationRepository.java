package gov.itms.notification.application;

import gov.itms.notification.domain.Notification;
import gov.itms.notification.domain.Recipient;
import gov.itms.notification.domain.Subscriptions;
import gov.itms.notification.domain.Template;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Templates, recipients, notifications, subscriptions and expiry watches (CC-070..CC-074). */
public interface NotificationRepository {

    void lockTemplate(String code);

    int nextTemplateVersion(String code);

    void insertTemplate(Template template);

    Optional<Template> latestTemplate(String code);

    Optional<Template> findTemplate(String code, int versionNo);

    List<Template> latestTemplates();

    Optional<Recipient> findRecipient(String id);

    void saveRecipient(Recipient recipient);

    void insertNotification(Notification notification);

    Optional<Notification> findNotification(UUID id);

    Optional<Notification> findByProviderId(String providerMessageId);

    void updateNotification(Notification notification);

    /** Newest first. {@code reference} optional. */
    List<Notification> byRecipient(String recipientId, String reference, int limit);

    /** Inbox messages of a recipient, newest first. */
    List<Notification> inbox(String recipientId, boolean unreadOnly, int limit);

    /** QUEUED notifications due for a send attempt, locked, skipping rows other instances hold. */
    List<Notification> dueForSending(Instant now, int limit);

    void saveSubscription(Subscriptions.EventSubscription subscription);

    List<Subscriptions.EventSubscription> subscriptions(String eventType);

    List<Subscriptions.EventSubscription> allSubscriptions();

    void saveExpiryWatch(Subscriptions.ExpiryWatch watch);

    /** A reminder due today: the watch and how many days before expiry it is. */
    record DueReminder(Subscriptions.ExpiryWatch watch, int offsetDays) {
    }

    /** Reminders due on {@code today} for one of {@code offsets} and not yet sent. */
    List<DueReminder> remindersDue(LocalDate today, List<Integer> offsets, int limit);

    /** Records that the reminder at {@code offsetDays} went out; false if it already had. */
    boolean markReminderSent(Subscriptions.SubjectType type, String subjectId, LocalDate expiryDate, int offsetDays);
}

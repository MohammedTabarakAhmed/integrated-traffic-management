package gov.itms.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One delivery of a rendered template to one recipient on one channel, with its delivery status (CC-074; ADR-050).
 *
 * <p>QUEUED → SENT (the gateway accepted it) → DELIVERED or FAILED (delivery receipt). A send that fails is retried
 * with a growing delay until the attempts run out (FAILED). The inbox is DELIVERED at once. Once DELIVERED or FAILED a
 * notification never changes, except that an inbox message can be marked read.
 */
public final class Notification {

    public enum Status { QUEUED, SENT, DELIVERED, FAILED }

    private final UUID id;
    private final String recipientId;
    private final String templateCode;
    private final int templateVersion;
    private final String category;
    private final boolean mandatory;
    private final DeliveryChannel channel;
    private final String destination;
    private final Language language;
    private final String subject;
    private final String body;
    private final String reference;
    private final String sourceEventId;
    private final Instant createdAt;
    private Status status;
    private int attempts;
    private Instant nextAttemptAt;
    private String providerMessageId;
    private String lastError;
    private Instant sentAt;
    private Instant deliveredAt;
    private Instant readAt;

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Notification(UUID id, String recipientId, String templateCode, int templateVersion, String category,
                         boolean mandatory, DeliveryChannel channel, String destination, Language language,
                         String subject, String body, String reference, String sourceEventId, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.recipientId = Objects.requireNonNull(recipientId);
        this.templateCode = Objects.requireNonNull(templateCode);
        this.templateVersion = templateVersion;
        this.category = Objects.requireNonNull(category);
        this.mandatory = mandatory;
        this.channel = Objects.requireNonNull(channel);
        this.destination = Objects.requireNonNull(destination);
        this.language = Objects.requireNonNull(language);
        this.subject = subject;
        this.body = Objects.requireNonNull(body);
        this.reference = reference;
        this.sourceEventId = sourceEventId;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static Notification queue(UUID id, Recipient recipient, Template template, DeliveryChannel channel,
                                     Template.Rendered rendered, String reference, String sourceEventId,
                                     Instant now) {
        Notification n = new Notification(id, recipient.id(), template.code(), template.versionNo(),
                template.category(), template.mandatory(), channel, recipient.destination(channel),
                recipient.language(), rendered.subject(), rendered.body(), reference, sourceEventId, now);
        if (channel == DeliveryChannel.INBOX) {
            n.status = Status.DELIVERED;
            n.sentAt = now;
            n.deliveredAt = now;
        } else {
            n.status = Status.QUEUED;
            n.nextAttemptAt = now;
        }
        return n;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public static Notification restore(UUID id, String recipientId, String templateCode, int templateVersion,
                                       String category, boolean mandatory, DeliveryChannel channel,
                                       String destination, Language language, String subject, String body,
                                       String reference, String sourceEventId, Instant createdAt, Status status,
                                       int attempts, Instant nextAttemptAt, String providerMessageId,
                                       String lastError, Instant sentAt, Instant deliveredAt, Instant readAt) {
        Notification n = new Notification(id, recipientId, templateCode, templateVersion, category, mandatory,
                channel, destination, language, subject, body, reference, sourceEventId, createdAt);
        n.status = status;
        n.attempts = attempts;
        n.nextAttemptAt = nextAttemptAt;
        n.providerMessageId = providerMessageId;
        n.lastError = lastError;
        n.sentAt = sentAt;
        n.deliveredAt = deliveredAt;
        n.readAt = readAt;
        return n;
    }

    /** The gateway accepted the message. */
    public void sent(String providerId, Instant now) {
        requireStatus(Status.QUEUED);
        attempts++;
        status = Status.SENT;
        providerMessageId = providerId;
        sentAt = now;
        nextAttemptAt = null;
        lastError = null;
    }

    /** The gateway refused or could not be reached: retry later, or give up after {@code maxAttempts}. */
    public void sendFailed(String error, int maxAttempts, Duration firstDelay, Instant now) {
        requireStatus(Status.QUEUED);
        attempts++;
        lastError = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500));
        if (attempts >= maxAttempts) {
            status = Status.FAILED;
            nextAttemptAt = null;
        } else {
            nextAttemptAt = now.plus(firstDelay.multipliedBy(1L << Math.min(attempts - 1, 10)));
        }
    }

    /** A delivery receipt from the gateway (CC-074). */
    public void receipt(boolean delivered, String error, Instant at) {
        if (status != Status.SENT) {
            throw DomainException.conflict("NOTIFICATION_NOT_SENT", "the notification is " + status,
                    List.of("CC-074"), status.name());
        }
        status = delivered ? Status.DELIVERED : Status.FAILED;
        if (delivered) {
            deliveredAt = at;
        } else {
            lastError = error == null ? "not delivered" : error.substring(0, Math.min(error.length(), 500));
        }
    }

    public void markRead(Instant now) {
        if (channel != DeliveryChannel.INBOX) {
            throw new IllegalStateException("only inbox messages are read");
        }
        if (readAt == null) {
            readAt = now;
        }
    }

    private void requireStatus(Status expected) {
        if (status != expected) {
            throw DomainException.conflict("NOTIFICATION_STATE", "the notification is " + status, List.of("CC-074"),
                    status.name());
        }
    }

    public UUID id() {
        return id;
    }

    public String recipientId() {
        return recipientId;
    }

    public String templateCode() {
        return templateCode;
    }

    public int templateVersion() {
        return templateVersion;
    }

    public String category() {
        return category;
    }

    public boolean mandatory() {
        return mandatory;
    }

    public DeliveryChannel channel() {
        return channel;
    }

    public String destination() {
        return destination;
    }

    public Language language() {
        return language;
    }

    public String subject() {
        return subject;
    }

    public String body() {
        return body;
    }

    public String reference() {
        return reference;
    }

    public String sourceEventId() {
        return sourceEventId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String providerMessageId() {
        return providerMessageId;
    }

    public String lastError() {
        return lastError;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public Instant deliveredAt() {
        return deliveredAt;
    }

    public Instant readAt() {
        return readAt;
    }
}

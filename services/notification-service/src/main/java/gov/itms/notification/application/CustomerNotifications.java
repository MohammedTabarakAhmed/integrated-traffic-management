package gov.itms.notification.application;

import gov.itms.notification.domain.DeliveryChannel;
import gov.itms.notification.domain.DomainException;
import gov.itms.notification.domain.Notification;
import gov.itms.notification.domain.Recipient;
import gov.itms.platform.audit.AuditRecorder;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in customer's own messages and channel preferences (CC-073, CH-002; ADR-067 d). Services address
 * notifications to a traffic file, so "own" means the recipient is the file traffic-file-service finds for the
 * national id in the customer's token. Mandatory notices still ignore preferences (CC-073).
 */
public class CustomerNotifications {

    /** Read own inbox and preferences; held only through the implicit CUSTOMER role (iam V61). */
    public static final String CUSTOMER_READ = "customer.notification.read";
    /** Change own contact points and channel preferences (a profile change: SEC-002 step-up). */
    public static final String CUSTOMER_PREFERENCES = "customer.notification.preferences";

    /** Port to the customer's traffic file; empty when they have none. */
    public interface OwnFile {
        Optional<String> fileNumber();
    }

    /** traffic-file-service did not answer, so the customer's own file is unknown (answered 503). */
    public static class TrafficFilesUnavailableException extends RuntimeException {
        public TrafficFilesUnavailableException(Throwable cause) {
            super("traffic-file-service is unavailable", cause);
        }
    }

    private final NotificationRepository repo;
    private final OwnFile ownFile;
    private final AuditRecorder audit;
    private final Clock clock;

    public CustomerNotifications(NotificationRepository repo, OwnFile ownFile, AuditRecorder audit, Clock clock) {
        this.repo = repo;
        this.ownFile = ownFile;
        this.audit = audit;
        this.clock = clock;
    }

    /** The customer's in-app messages, newest first. */
    @Transactional(readOnly = true)
    public List<Notification> inbox(boolean unreadOnly, int limit, Caller caller) {
        require(caller, CUSTOMER_READ);
        if (limit < 1 || limit > 200) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1..200", List.of("CC-070"), "limit");
        }
        return repo.inbox(fileNumber(), unreadOnly, limit);
    }

    /** Marks one of the customer's own in-app messages read; any other answers as not found. */
    @Transactional
    public Notification markRead(UUID id, Caller caller) {
        require(caller, CUSTOMER_READ);
        String file = fileNumber();
        Notification n = repo.findNotification(id)
                .filter(x -> x.recipientId().equals(file) && x.channel() == DeliveryChannel.INBOX)
                .orElseThrow(() -> notFound(id));
        n.markRead(clock.instant());
        repo.updateNotification(n);
        return n;
    }

    /** The customer's contact points (as kept) and channel preferences. */
    @Transactional(readOnly = true)
    public Recipient preferences(Caller caller) {
        require(caller, CUSTOMER_READ);
        String file = fileNumber();
        return repo.findRecipient(file).orElseGet(() -> Recipient.unknown(file));
    }

    /** Saves the customer's own contact points and preferences; audited with masked values (CC-073, CC-120). */
    @Transactional
    public Recipient savePreferences(java.util.function.Function<String, Recipient> recipientOf, Caller caller) {
        NotificationService.channelOf(caller);
        require(caller, CUSTOMER_PREFERENCES);
        String file = fileNumber();
        Recipient recipient = recipientOf.apply(file);
        Optional<Recipient> before = repo.findRecipient(file);
        repo.saveRecipient(recipient);
        audit.record("RECIPIENT_SAVED", "Recipient", file, before.map(NotificationService::masked).orElse(null),
                NotificationService.masked(recipient), "by the customer (OWNER)");
        return recipient;
    }

    private String fileNumber() {
        return ownFile.fileNumber().orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                "OWN_FILE_NOT_FOUND", "you have no traffic file yet", List.of("DL-001")));
    }

    private static void require(Caller caller, String permission) {
        if (!caller.grants().holds(permission)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this here", List.of("CC-001"));
        }
    }

    private static DomainException notFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "NOTIFICATION_NOT_FOUND",
                "notification " + id + " not found", List.of("CC-070"));
    }
}

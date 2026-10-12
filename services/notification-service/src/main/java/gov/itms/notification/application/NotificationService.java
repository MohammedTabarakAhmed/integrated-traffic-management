package gov.itms.notification.application;

import gov.itms.notification.domain.DeliveryChannel;
import gov.itms.notification.domain.DomainException;
import gov.itms.notification.domain.Notification;
import gov.itms.notification.domain.Recipient;
import gov.itms.notification.domain.Subscriptions;
import gov.itms.notification.domain.Template;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.ProcessedEvents;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notifications (CC-070..CC-074; ADR-050).
 * <ul>
 *   <li>bilingual templates per channel, published as versions ({@value #TEMPLATE_MANAGE}, CC-070);</li>
 *   <li>services send a template to a recipient ({@value #SEND}); domain events trigger templates through
 *       subscriptions ({@value #SUBSCRIPTION_MANAGE}, CC-071); services register expiries
 *       ({@value #EXPIRY_REGISTER}) and a daily job sends reminders at the configured offsets (CC-072);</li>
 *   <li>channels follow the recipient's preferences per category, except mandatory legal notices (CC-073);
 *       customers keep their own contacts and preferences, staff with {@value #RECIPIENT_MANAGE} for them;</li>
 *   <li>every delivery has a status: queued, sent, delivered, failed, with attempts and the gateway's receipts
 *       ({@value #RECEIPT}, CC-074).</li>
 * </ul>
 */
public class NotificationService {

    public static final String TEMPLATE_MANAGE = "notification.template.manage";
    public static final String TEMPLATE_READ = "notification.template.read";
    public static final String SEND = "notification.send";
    public static final String READ = "notification.read";
    public static final String RECIPIENT_MANAGE = "notification.recipient.manage";
    public static final String RECEIPT = "notification.receipt";
    public static final String SUBSCRIPTION_MANAGE = "notification.subscription.manage";
    public static final String EXPIRY_REGISTER = "notification.expiry.register";
    static final String CONSUMER = "notification-service";
    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    /** Delivery settings (configuration, NFR-042). */
    public record Settings(int maxAttempts, Duration retryDelay, List<Integer> reminderOffsetDays,
                           List<ExpiryEvent> expiryEvents) {
        public Settings {
            reminderOffsetDays = List.copyOf(reminderOffsetDays);
            expiryEvents = expiryEvents == null ? List.of() : List.copyOf(expiryEvents);
        }

        public Settings(int maxAttempts, Duration retryDelay, List<Integer> reminderOffsetDays) {
            this(maxAttempts, retryDelay, reminderOffsetDays, List.of());
        }
    }

    /**
     * An owning service's event that registers or moves an expiry (CC-072): the event's {@code subjectField},
     * {@code recipientField} and {@code expiryField} (ISO date) give the watch. An event without the expiry field (for
     * example a permit status change other than issue) registers nothing.
     */
    public record ExpiryEvent(String eventType, Subscriptions.SubjectType subjectType, String subjectField,
                              String recipientField, String expiryField) {
        public ExpiryEvent {
            if (eventType == null || eventType.isBlank() || subjectType == null || subjectField == null
                    || recipientField == null || expiryField == null) {
                throw new IllegalArgumentException("an expiry event needs eventType, subjectType, subjectField, "
                        + "recipientField and expiryField (CC-072)");
            }
        }
    }

    private final NotificationRepository repo;
    private final ChannelGateway gateway;
    private final AuditRecorder audit;
    private final ProcessedEvents processed;
    private final Settings settings;
    private final Clock clock;

    public NotificationService(NotificationRepository repo, ChannelGateway gateway, AuditRecorder audit,
                               ProcessedEvents processed, Settings settings, Clock clock) {
        this.repo = repo;
        this.gateway = gateway;
        this.audit = audit;
        this.processed = processed;
        this.settings = settings;
        this.clock = clock;
    }

    // ---- templates (CC-070)

    @Transactional
    public Template publishTemplate(String code, String category, boolean mandatory,
                                    Map<DeliveryChannel, Template.Content> contents, String reason, Caller caller) {
        channelOf(caller);
        require(caller.grants().holds(TEMPLATE_MANAGE));
        String why = reason(reason);
        repo.lockTemplate(code);
        Template t = new Template(code, repo.nextTemplateVersion(code), category, mandatory, contents,
                caller.actor(), clock.instant());
        repo.insertTemplate(t);
        audit.record("NOTIFICATION_TEMPLATE_PUBLISHED", "NotificationTemplate", code + ":" + t.versionNo(), null,
                Map.of("versionNo", t.versionNo(), "category", category, "mandatory", mandatory,
                        "channels", t.contents().keySet().stream().map(Enum::name).sorted().toList()), why);
        return t;
    }

    @Transactional(readOnly = true)
    public Template template(String code, Caller caller) {
        require(caller.grants().holds(TEMPLATE_READ) || caller.grants().holds(TEMPLATE_MANAGE));
        return repo.latestTemplate(code).orElseThrow(() -> templateNotFound(code));
    }

    @Transactional(readOnly = true)
    public List<Template> templates(Caller caller) {
        require(caller.grants().holds(TEMPLATE_READ) || caller.grants().holds(TEMPLATE_MANAGE));
        return repo.latestTemplates();
    }

    // ---- sending (CC-070, CC-073)

    @Transactional
    public List<Notification> send(String templateCode, String recipientId, Map<String, String> data,
                                   String reference, Caller caller) {
        channelOf(caller);
        require(caller.grants().holds(SEND));
        List<Notification> sent = queue(templateCode, recipientId, data, reference, null, clock.instant());
        audit.record("NOTIFICATION_REQUESTED", "Notification", recipientId, null,
                Map.of("template", templateCode, "deliveries", sent.size()), null);
        return sent;
    }

    private List<Notification> queue(String templateCode, String recipientId, Map<String, String> data,
                                     String reference, String eventId, Instant now) {
        Template template = repo.latestTemplate(templateCode).orElseThrow(() -> templateNotFound(templateCode));
        Recipient recipient = repo.findRecipient(recipientId).orElseGet(() -> Recipient.unknown(recipientId));
        Set<DeliveryChannel> channels = recipient.channelsFor(template);
        List<Notification> created = new ArrayList<>();
        for (DeliveryChannel c : channels) {
            Notification n = Notification.queue(UUID.randomUUID(), recipient, template, c,
                    template.render(c, recipient.language(), data == null ? Map.of() : data), reference, eventId,
                    now);
            repo.insertNotification(n);
            created.add(n);
        }
        return created;
    }

    @Transactional
    public Notification get(UUID id, Caller caller) {
        Notification n = repo.findNotification(id).orElseThrow(() -> notFound(id));
        boolean own = n.recipientId().equals(caller.actor().id());
        require(own || caller.grants().holds(READ));
        if (!own) {
            audit.record("NOTIFICATION_VIEWED", "Notification", id.toString(), null, null, null);
        }
        return n;
    }

    @Transactional
    public List<Notification> byRecipient(String recipientId, String reference, int limit, Caller caller) {
        require(caller.grants().holds(READ));
        checkLimit(limit);
        audit.record("NOTIFICATIONS_LISTED", "Recipient", recipientId, null, null, null);
        return repo.byRecipient(recipientId, reference, limit);
    }

    // ---- inbox

    @Transactional(readOnly = true)
    public List<Notification> inbox(boolean unreadOnly, int limit, Caller caller) {
        checkLimit(limit);
        return repo.inbox(caller.actor().id(), unreadOnly, limit);
    }

    @Transactional
    public Notification markRead(UUID id, Caller caller) {
        Notification n = repo.findNotification(id).orElseThrow(() -> notFound(id));
        if (!n.recipientId().equals(caller.actor().id()) || n.channel() != DeliveryChannel.INBOX) {
            throw notFound(id);
        }
        n.markRead(clock.instant());
        repo.updateNotification(n);
        return n;
    }

    // ---- recipients (CC-073)

    @Transactional
    public Recipient saveRecipient(Recipient recipient, Caller caller) {
        channelOf(caller);
        boolean own = recipient.id().equals(caller.actor().id());
        require(own || caller.grants().holds(RECIPIENT_MANAGE));
        Optional<Recipient> before = repo.findRecipient(recipient.id());
        repo.saveRecipient(recipient);
        audit.record("RECIPIENT_SAVED", "Recipient", recipient.id(), before.map(NotificationService::masked).orElse(null),
                masked(recipient), null);
        return recipient;
    }

    @Transactional
    public Recipient recipient(String id, Caller caller) {
        boolean own = id.equals(caller.actor().id());
        require(own || caller.grants().holds(RECIPIENT_MANAGE) || caller.grants().holds(READ));
        if (!own) {
            audit.record("RECIPIENT_VIEWED", "Recipient", id, null, null, null);
        }
        return repo.findRecipient(id).orElseGet(() -> Recipient.unknown(id));
    }

    // ---- event subscriptions (CC-071)

    @Transactional
    public Subscriptions.EventSubscription saveSubscription(Subscriptions.EventSubscription s, Caller caller) {
        channelOf(caller);
        require(caller.grants().holds(SUBSCRIPTION_MANAGE));
        repo.latestTemplate(s.templateCode()).orElseThrow(() -> templateNotFound(s.templateCode()));
        repo.saveSubscription(s);
        audit.record("NOTIFICATION_SUBSCRIPTION_SAVED", "EventSubscription", s.eventType() + ":" + s.templateCode(),
                null, Map.of("active", s.active(), "recipientField", s.recipientField()), null);
        return s;
    }

    @Transactional(readOnly = true)
    public List<Subscriptions.EventSubscription> subscriptions(Caller caller) {
        require(caller.grants().holds(SUBSCRIPTION_MANAGE) || caller.grants().holds(TEMPLATE_READ));
        return repo.allSubscriptions();
    }

    /**
     * Handles one domain event (CC-071), at most once per event id. Each active subscription of the event type sends
     * its template to the recipient named in the event data; an event without that field is skipped and logged.
     */
    @Transactional
    public int onEvent(String eventId, String eventType, Map<String, Object> data) {
        if (!processed.firstDelivery(CONSUMER, eventId)) {
            return 0;
        }
        CurrentActor system = CurrentActor.system("notification-event-consumer");
        Instant now = clock.instant();
        Map<String, String> values = flatten(data);
        int count = 0;
        for (Subscriptions.EventSubscription s : repo.subscriptions(eventType)) {
            if (!s.active()) {
                continue;
            }
            String recipientId = values.get(s.recipientField());
            if (recipientId == null || recipientId.isBlank()) {
                LOG.info("Event {} of type {} has no {}; template {} not sent", eventId, eventType,
                        s.recipientField(), s.templateCode());
                continue;
            }
            try {
                List<Notification> sent = queue(s.templateCode(), recipientId, values,
                        s.referenceField() == null ? null : values.get(s.referenceField()), eventId, now);
                count += sent.size();
                audit.recordAs(system, "NOTIFICATION_TRIGGERED", "Notification", recipientId, null,
                        Map.of("event", eventType, "eventId", eventId, "template", s.templateCode(),
                                "deliveries", sent.size()), null);
            } catch (DomainException e) {
                LOG.warn("Event {} of type {}: template {} not sent ({})", eventId, eventType, s.templateCode(),
                        e.code());
            }
        }
        for (ExpiryEvent x : settings.expiryEvents()) {
            if (x.eventType().equals(eventType)) {
                watchExpiry(x, values, eventId, system);
            }
        }
        return count;
    }

    private void watchExpiry(ExpiryEvent x, Map<String, String> values, String eventId, CurrentActor system) {
        String expiry = values.get(x.expiryField());
        if (expiry == null || expiry.isBlank()) {
            return;
        }
        Subscriptions.ExpiryWatch watch;
        try {
            watch = new Subscriptions.ExpiryWatch(x.subjectType(), values.get(x.subjectField()),
                    values.get(x.recipientField()), LocalDate.parse(expiry.length() > 10 ? expiry.substring(0, 10)
                            : expiry), values.get(x.subjectField()));
        } catch (RuntimeException e) {
            LOG.warn("Event {} of type {} has no usable expiry ({}); no reminder registered", eventId, x.eventType(),
                    e.getClass().getSimpleName());
            return;
        }
        repo.saveExpiryWatch(watch);
        audit.recordAs(system, "EXPIRY_REGISTERED", "ExpiryWatch", watch.subjectType() + ":" + watch.subjectId(),
                null, Map.of("expiryDate", watch.expiryDate().toString(), "event", x.eventType(), "eventId", eventId),
                null);
    }

    // ---- expiry reminders (CC-072)

    @Transactional
    public Subscriptions.ExpiryWatch registerExpiry(Subscriptions.ExpiryWatch watch, Caller caller) {
        channelOf(caller);
        require(caller.grants().holds(EXPIRY_REGISTER));
        repo.saveExpiryWatch(watch);
        audit.record("EXPIRY_REGISTERED", "ExpiryWatch", watch.subjectType() + ":" + watch.subjectId(), null,
                Map.of("expiryDate", watch.expiryDate().toString()), null);
        return watch;
    }

    /** Sends the reminders due today (CC-072); run by the daily job. */
    @Transactional
    public int sendDueReminders(int limit) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        CurrentActor system = CurrentActor.system("notification-expiry-job");
        int sent = 0;
        for (NotificationRepository.DueReminder due : repo.remindersDue(today, settings.reminderOffsetDays(),
                limit)) {
            Subscriptions.ExpiryWatch w = due.watch();
            if (!repo.markReminderSent(w.subjectType(), w.subjectId(), w.expiryDate(), due.offsetDays())) {
                continue;
            }
            Map<String, String> data = new LinkedHashMap<>();
            data.put("subjectType", w.subjectType().name());
            data.put("subjectId", w.subjectId());
            data.put("expiryDate", w.expiryDate().toString());
            data.put("daysLeft", String.valueOf(due.offsetDays()));
            try {
                List<Notification> created = queue(w.templateCode(), w.recipientId(), data, w.reference(), null,
                        now);
                sent += created.size();
                audit.recordAs(system, "EXPIRY_REMINDER_SENT", "ExpiryWatch",
                        w.subjectType() + ":" + w.subjectId(), null,
                        Map.of("offsetDays", due.offsetDays(), "deliveries", created.size()), null);
            } catch (DomainException e) {
                LOG.warn("Expiry reminder for {} {} not sent ({})", w.subjectType(), w.subjectId(), e.code());
            }
        }
        return sent;
    }

    // ---- delivery (CC-074)

    /** Sends queued notifications due now; run by the dispatcher job. Returns how many were attempted. */
    @Transactional
    public int dispatch(int limit) {
        Instant now = clock.instant();
        List<Notification> due = repo.dueForSending(now, limit);
        for (Notification n : due) {
            try {
                String providerId = gateway.send(n.channel(), n.destination(), n.subject(), n.body(),
                        n.id().toString());
                n.sent(providerId, clock.instant());
            } catch (ChannelGateway.GatewayException e) {
                n.sendFailed(e.getMessage(), settings.maxAttempts(), settings.retryDelay(), clock.instant());
            }
            repo.updateNotification(n);
        }
        return due.size();
    }

    /** A delivery receipt from a gateway (CC-074). Unknown message ids are 404; a repeated receipt is 409. */
    @Transactional
    public Notification receipt(String providerMessageId, boolean delivered, String error, Instant at,
                                Caller caller) {
        require(caller.grants().holds(RECEIPT));
        Notification n = repo.findByProviderId(providerMessageId).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "no notification with this message id",
                List.of("CC-074")));
        n.receipt(delivered, error, at == null ? clock.instant() : at);
        repo.updateNotification(n);
        audit.record(delivered ? "NOTIFICATION_DELIVERED" : "NOTIFICATION_FAILED", "Notification",
                n.id().toString(), null, Map.of("status", n.status().name()), null);
        return n;
    }

    // ---- helpers

    static Map<String, String> flatten(Map<String, Object> data) {
        Map<String, String> m = new LinkedHashMap<>();
        if (data != null) {
            data.forEach((k, v) -> {
                if (v != null && !(v instanceof Map) && !(v instanceof List)) {
                    m.put(k, String.valueOf(v));
                }
            });
        }
        return m;
    }

    static Map<String, Object> masked(Recipient r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("language", r.language().name());
        m.put("phone", Recipient.mask(DeliveryChannel.SMS, r.phone()));
        m.put("email", Recipient.mask(DeliveryChannel.EMAIL, r.email()));
        m.put("push", r.pushToken() == null ? null : "device");
        m.put("preferences", r.preferences().keySet());
        return m;
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1..200", List.of("CC-074"), "limit");
        }
    }

    static String reason(String value) {
        String v = value == null ? "" : value.strip();
        if (v.length() < 3 || v.length() > 500 || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("CC-070"));
        }
        return v;
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with notifications", List.of("CC-001"));
        }
    }

    static void channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw DomainException.validation("CHANNEL_REQUIRED", "the request does not name its channel (X-Channel)",
                    List.of("CC-120"));
        }
    }

    private static DomainException templateNotFound(String code) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "TEMPLATE_NOT_FOUND",
                "template " + code + " not found", List.of("CC-070"), code);
    }

    private static DomainException notFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "NOTIFICATION_NOT_FOUND",
                "notification " + id + " not found", List.of("CC-074"));
    }
}

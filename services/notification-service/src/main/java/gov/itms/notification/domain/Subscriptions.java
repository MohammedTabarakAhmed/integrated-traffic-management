package gov.itms.notification.domain;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** Event subscriptions (CC-071) and expiry watches (CC-072). */
public final class Subscriptions {

    private Subscriptions() {
    }

    private static final Pattern EVENT_TYPE = Pattern.compile("^itms\\.[a-z0-9-]+\\.[a-z0-9-]+$");
    private static final Pattern FIELD = Pattern.compile("^[a-zA-Z][a-zA-Z0-9]{0,39}$");
    private static final Pattern CODE = Pattern.compile(Template.CODE);

    /**
     * Which template a domain event triggers (CC-071), and which field of the event data names the recipient and the
     * business reference. Active until deactivated; one subscription per event type and template.
     */
    public record EventSubscription(String eventType, String templateCode, String recipientField,
                                    String referenceField, boolean active) {
        public EventSubscription {
            if (eventType == null || !EVENT_TYPE.matcher(eventType).matches()) {
                throw Template.invalid("eventType");
            }
            if (templateCode == null || !CODE.matcher(templateCode).matches()) {
                throw Template.invalid("templateCode");
            }
            if (recipientField == null || !FIELD.matcher(recipientField).matches()) {
                throw Template.invalid("recipientField");
            }
            if (referenceField != null && !FIELD.matcher(referenceField).matches()) {
                throw Template.invalid("referenceField");
            }
        }
    }

    /** Kinds of expiring things reminded about (CC-072). */
    public enum SubjectType { LICENCE, REGISTRATION, PERMIT, INSURANCE }

    /**
     * An expiry the owning service registered (CC-072): reminders go out at the configured offsets before it. A new
     * expiry date for the same subject replaces the old one and resets the sent offsets.
     */
    public record ExpiryWatch(SubjectType subjectType, String subjectId, String recipientId, LocalDate expiryDate,
                              String reference) {
        public ExpiryWatch {
            if (subjectType == null) {
                throw Template.invalid("subjectType");
            }
            if (subjectId == null || !subjectId.matches("^[A-Za-z0-9-]{1,60}$")) {
                throw Template.invalid("subjectId");
            }
            if (recipientId == null || !recipientId.matches("^[A-Za-z0-9._:-]{1,100}$")) {
                throw Template.invalid("recipientId");
            }
            if (expiryDate == null) {
                throw Template.invalid("expiryDate");
            }
            if (reference != null && reference.length() > 60) {
                throw Template.invalid("reference");
            }
        }

        /** The template of a subject type's reminders, for example {@code EXPIRY_REMINDER_LICENCE}. */
        public String templateCode() {
            return "EXPIRY_REMINDER_" + subjectType.name();
        }
    }

}

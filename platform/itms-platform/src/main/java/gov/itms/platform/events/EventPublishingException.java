package gov.itms.platform.events;

/** The broker did not acknowledge an event; the outbox row stays unpublished and is retried. */
public class EventPublishingException extends RuntimeException {

    public EventPublishingException(String message, Throwable cause) {
        super(message, cause);
    }
}

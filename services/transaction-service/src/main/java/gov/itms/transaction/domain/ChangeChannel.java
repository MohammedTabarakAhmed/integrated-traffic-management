package gov.itms.transaction.domain;

/**
 * Channel recorded with a status change (CC-023): one of the {@link Channel}s a request came through, or
 * {@link #SYSTEM} for a change the system makes on its own schedule, such as Draft expiry (CC-024). {@code SYSTEM} is
 * never a channel a request can use, so it is kept out of {@link Channel}.
 */
public enum ChangeChannel {
    PORTAL, APP, BACKOFFICE, PARTNER, KIOSK, CALLCENTRE, FIELD, ASSISTANT, AGENT, SYSTEM;

    public static ChangeChannel of(Channel channel) {
        return valueOf(channel.name());
    }
}

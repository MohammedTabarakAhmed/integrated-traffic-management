package gov.itms.platform.security;

import java.util.List;

/**
 * Who is acting, through which channel and from where (CC-023, CC-120).
 *
 * @param userId        stable subject identifier ({@code sub})
 * @param username      display/login name, for audit readability
 * @param roles         role names at the time of the action
 * @param channel       one of {@link #CHANNELS}, or {@code UNKNOWN}
 * @param ipAddress     client address as seen by the service
 * @param correlationId request correlation id
 */
public record CurrentActor(String userId, String username, List<String> roles, String channel, String ipAddress,
                           String correlationId) {

    /** Channel values from API_SPECIFICATION §2. */
    public static final List<String> CHANNELS = List.of(
            "PORTAL", "APP", "BACKOFFICE", "PARTNER", "KIOSK", "CALLCENTRE", "FIELD", "ASSISTANT", "AGENT", "SYSTEM");

    public CurrentActor {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public static CurrentActor system(String name) {
        return new CurrentActor(name, name, List.of(), "SYSTEM", null, null);
    }
}

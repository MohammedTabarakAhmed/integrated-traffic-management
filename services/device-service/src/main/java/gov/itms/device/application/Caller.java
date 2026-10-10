package gov.itms.device.application;

import java.util.Objects;

/** Current device-service actor and resolved permission grants. */
public record Caller(String id, String username, Grants grants) {

    public interface Grants {
        boolean allows(String permission, String region);
        boolean holds(String permission);
    }

    public Caller {
        Objects.requireNonNull(id);
        Objects.requireNonNull(username);
        Objects.requireNonNull(grants);
    }
}

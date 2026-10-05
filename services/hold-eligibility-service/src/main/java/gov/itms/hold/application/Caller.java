package gov.itms.hold.application;

import gov.itms.hold.domain.Actor;
import gov.itms.hold.domain.Channel;
import java.util.List;
import java.util.Objects;

/**
 * Who calls a hold command, through which channel ({@code null} when the request named no known channel), and which
 * of their grants reach a module (CC-001 data scopes; holds are national, so only the module scopes them, Q-053).
 */
public record Caller(Actor actor, Channel channel, Grants grants) {

    public interface Grants {

        /** True if some grant holds {@code permission} and reaches {@code module}. */
        boolean allows(String permission, String module);

        /** True if a grant of one of {@code roles} holds {@code permission} and reaches {@code module}. */
        boolean allowsThroughRole(String permission, List<String> roles, String module);
    }

    public Caller {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(grants);
    }
}

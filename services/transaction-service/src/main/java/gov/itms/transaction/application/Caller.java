package gov.itms.transaction.application;

import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.Channel;
import java.util.Objects;

/**
 * Who calls a transaction command, through which channel ({@code null} when the request named no known channel), and
 * which of their permissions reach a region and module (CC-001 data scopes).
 */
public record Caller(Actor actor, Channel channel, Scopes scopes) {

    /** True if the caller holds {@code permission} in a grant that reaches {@code region} and {@code module}. */
    @FunctionalInterface
    public interface Scopes {
        boolean allows(String permission, String region, String module);
    }

    public Caller {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(scopes);
    }
}

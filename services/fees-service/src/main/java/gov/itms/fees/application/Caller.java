package gov.itms.fees.application;

import gov.itms.fees.domain.Actor;
import gov.itms.fees.domain.Channel;
import java.util.Objects;

/**
 * Who calls a fees command, through which channel ({@code null} when the request named no known channel), and
 * which of their grants reach a region (CC-001 data scopes).
 */
public record Caller(Actor actor, Channel channel, Grants grants) {

    public interface Grants {

        /** True if some grant holds {@code permission} and reaches {@code region}. */
        boolean allowsInRegion(String permission, String region);

        /** True if some grant, of any scope, holds {@code permission}. */
        boolean holds(String permission);
    }

    public Caller {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(grants);
    }
}

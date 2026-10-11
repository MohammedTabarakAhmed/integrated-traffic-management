package gov.itms.violation.application;

import gov.itms.violation.domain.Actor;
import gov.itms.violation.domain.Channel;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Who calls a violations command, through which channel ({@code null} when the request named no known channel), and
 * which of their grants reach a region (CC-001 data scopes).
 */
public record Caller(Actor actor, Channel channel, Grants grants, String correlationId) {

    public interface Grants {

        /** True if some grant holds {@code permission} and reaches {@code region}. */
        boolean allowsInRegion(String permission, String region);

        /** True if some grant, of any scope, holds {@code permission}. */
        boolean holds(String permission);

        /**
         * The caller's approval limit of {@code limitType} in {@code currency} for {@code region} (CC-003); empty
         * means no authority to approve.
         */
        default OptionalLong approvalLimit(String limitType, String currency, String region) {
            return OptionalLong.empty();
        }
    }

    public Caller {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(grants);
    }

    public Caller(Actor actor, Channel channel, Grants grants) {
        this(actor, channel, grants, null);
    }
}

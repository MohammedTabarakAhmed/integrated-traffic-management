package gov.itms.transaction.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One status change (CC-023): who, through which channel, when, why and from which status. {@code sequence} 1 is the
 * creation, with no previous status.
 */
public record StatusChange(long sequence, TransactionStatus previousStatus, TransactionStatus status,
                           StatusReason reason, String note, Actor actor, Channel channel, Instant changedAt) {

    public StatusChange {
        Objects.requireNonNull(status);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(channel);
        Objects.requireNonNull(changedAt);
        if (sequence < 1 || (sequence == 1) != (previousStatus == null)) {
            throw new IllegalArgumentException("only the first change has no previous status");
        }
    }
}

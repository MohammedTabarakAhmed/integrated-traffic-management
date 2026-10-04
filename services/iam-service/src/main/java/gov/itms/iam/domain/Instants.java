package gov.itms.iam.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Instants as iam-service stores them: PostgreSQL {@code timestamptz} keeps microseconds. Every instant a domain object
 * holds is cut to that precision first, so a response or an audit record (CC-120) never shows a value that differs from
 * the stored one.
 */
public final class Instants {

    private Instants() {
    }

    /** {@code value} truncated to microseconds; {@code null} stays {@code null}. */
    public static Instant stored(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }
}

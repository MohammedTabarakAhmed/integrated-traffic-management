package gov.itms.iam.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * How often access must be reviewed (SEC-007: "every quarter"). The period is configuration, never code
 * ({@code itms.iam.access-review.period}, ISO-8601, default {@code P3M}); it is applied as a rolling period on the
 * UTC clock since an assignment was granted or last kept (Q-038).
 *
 * @param period positive review period, at most one quarter (NFR-042)
 */
public record AccessReviewPolicy(Period period) {

    public AccessReviewPolicy {
        Objects.requireNonNull(period, "review period required");
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("the access review period must be positive, got " + period);
        }
        if (!withinOneQuarter(period.normalized())) {
            throw new IllegalArgumentException("the access review period must be at most one quarter (P3M or P92D; "
                    + "SEC-007, NFR-042), got " + period);
        }
    }

    /** Approved range (NFR-042): reviews at least every quarter, so never longer than P3M (or 92 days). */
    private static boolean withinOneQuarter(Period p) {
        if (p.getYears() != 0 || p.getMonths() < 0 || p.getDays() < 0) {
            return false;
        }
        return p.getMonths() == 3 ? p.getDays() == 0 : p.getMonths() * 31L + p.getDays() <= 92;
    }

    /** Parses an ISO-8601 period such as {@code P3M}; fails fast so a bad configuration stops start-up. */
    public static AccessReviewPolicy parse(String iso) {
        if (iso == null || iso.isBlank()) {
            throw new IllegalArgumentException("the access review period is not configured");
        }
        try {
            return new AccessReviewPolicy(Period.parse(iso.strip()));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("the access review period is not an ISO-8601 period: " + iso, e);
        }
    }

    /** Start of the current review window: a KEEP decision at or after this instant is recent enough. */
    public Instant reviewedSince(Instant now) {
        return now.atZone(ZoneOffset.UTC).minus(period).toInstant();
    }

    /** When access granted or last kept at {@code from} becomes due for review again. */
    public Instant dueAfter(Instant from) {
        return from.atZone(ZoneOffset.UTC).plus(period).toInstant();
    }
}

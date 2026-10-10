package gov.itms.violation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A hold placed at hold-eligibility-service because a violation stayed unpaid longer than the configured period
 * (VF-023; ADR-058). Interim rules (Q-012, Q-074): a violation charged to an identified driver holds the driver's
 * traffic file; one charged to the owner holds the vehicle (by VIN), or the owner's file when the report has no VIN.
 * The hold is lifted once the violation is paid or cancelled; a violation reopened by a cancelled receipt gets a new
 * hold when it is overdue again.
 */
public record UnpaidHold(UUID id, UUID violationId, String violationNumber, Subject subject, String holdId,
                         Instant placedAt, Instant liftedAt) {

    public enum SubjectType { TRAFFIC_FILE, VEHICLE }

    public record Subject(SubjectType type, String id) {
    }

    /** When unpaid violations get a hold: switched on, after how long since recording, and how many per run. */
    public record Policy(boolean enabled, Duration after, int batchSize) {
        public Policy {
            if (after.compareTo(Duration.ofMinutes(1)) < 0 || after.compareTo(Duration.ofDays(3650)) > 0) {
                throw new IllegalArgumentException("the unpaid period must be 1 minute to 3650 days");
            }
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException("the batch size must be 1 to 1000");
            }
        }

        public static Policy disabled() {
            return new Policy(false, Duration.ofDays(60), 100);
        }
    }

    /** Whose hold an unpaid violation places (Q-012). */
    public static Subject subjectOf(Violation v) {
        Violation.Report r = v.report();
        if (r.driverFileNumber() != null) {
            return new Subject(SubjectType.TRAFFIC_FILE, r.driverFileNumber());
        }
        if (r.vin() != null) {
            return new Subject(SubjectType.VEHICLE, r.vin());
        }
        return new Subject(SubjectType.TRAFFIC_FILE, r.responsibleFileNumber());
    }

    /** The source reference given to hold-eligibility-service; unique per placed hold (CC-033). */
    public String sourceRef() {
        return "UNPAID-" + id;
    }

    public boolean active() {
        return liftedAt == null;
    }
}

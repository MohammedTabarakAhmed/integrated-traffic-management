package gov.itms.violation.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Black points of a traffic file (VF-030..VF-033; ADR-056). Each point-bearing violation adds an entry that is active
 * until its expiry (default 12 months from the violation date, VF-031). Reaching the suspension threshold (default 24)
 * orders a suspension whose length grows with each earlier suspension (default 3, 6, 12 months, VF-032); the entries
 * counted for it are consumed so they do not order a second one. Warning thresholds (default 12 and 18) notify the
 * driver (VF-033).
 */
public final class BlackPoints {

    private BlackPoints() {
    }

    public enum EntryStatus { ACTIVE, CONSUMED, CANCELLED }

    public record Entry(UUID id, String fileNumber, UUID violationId, int points, LocalDate occurredOn,
                        LocalDate expiresOn, EntryStatus status) {
        public boolean activeOn(LocalDate day) {
            return status == EntryStatus.ACTIVE && day.isBefore(expiresOn);
        }
    }

    public record Suspension(UUID id, String fileNumber, int ordinal, LocalDate from, LocalDate until,
                             int pointsAtOrder, String holdId, UUID triggeredBy) {
        public boolean activeOn(LocalDate day) {
            return !day.isBefore(from) && !day.isAfter(until);
        }
    }

    /** Settings (configuration, NFR-042). */
    public record Policy(int expiryMonths, int suspensionThreshold, List<Integer> suspensionMonths,
                         List<Integer> warningThresholds) {
        public Policy {
            suspensionMonths = List.copyOf(suspensionMonths);
            warningThresholds = List.copyOf(warningThresholds);
            if (expiryMonths < 1 || expiryMonths > 120 || suspensionThreshold < 1 || suspensionThreshold > 1000
                    || suspensionMonths.isEmpty() || suspensionMonths.stream().anyMatch(m -> m < 1 || m > 120)
                    || warningThresholds.stream().anyMatch(t -> t < 1 || t >= suspensionThreshold)) {
                throw new IllegalArgumentException("black point settings out of range (VF-031..VF-033)");
            }
        }

        /** The length of the n-th suspension (1-based): the last configured length repeats. */
        public int monthsOf(int ordinal) {
            return suspensionMonths.get(Math.min(ordinal, suspensionMonths.size()) - 1);
        }
    }

    /** Sum of the active points on a day. */
    public static int active(List<Entry> entries, LocalDate day) {
        return entries.stream().filter(e -> e.activeOn(day)).mapToInt(Entry::points).sum();
    }

    /** Warning thresholds crossed by going from {@code before} to {@code after} points (VF-033). */
    public static List<Integer> warningsCrossed(Policy policy, int before, int after) {
        return policy.warningThresholds().stream().filter(t -> before < t && after >= t).sorted().toList();
    }
}

package gov.itms.violation.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The Authority's own instalment plan for a file's open violations (VF-071, VF-073; ADR-062). The violations stay
 * OPEN while the plan runs; their payables are replaced by one payable per monthly instalment. While every
 * instalment is paid on time (within the grace period) the plan is ACTIVE and covers the violations, so no unpaid
 * hold applies (VF-023); an instalment unpaid after its due date plus the grace period makes the plan LATE and the
 * holds return until it is paid. When every instalment is paid the plan is COMPLETED and the violations PAID.
 */
public record InstalmentPlan(UUID id, String number, String fileNumber, String region, String currency,
                             long totalMinor, int months, int graceDays, Status status, String createdBy,
                             Instant createdAt, Instant closedAt, List<String> violationNumbers,
                             List<Instalment> instalments) {

    public enum Status { ACTIVE, LATE, COMPLETED }

    /** One monthly instalment; {@code payableId} is its payable at payment-service. */
    public record Instalment(int seq, LocalDate dueDate, long amountMinor, UUID payableId, boolean paid,
                             Instant paidAt) {

        public Instalment {
            Objects.requireNonNull(dueDate);
            if (seq < 1 || amountMinor <= 0 || paid != (paidAt != null)) {
                throw new IllegalArgumentException("invalid instalment");
            }
        }

        /** Unpaid after its due date plus the grace period: the plan no longer covers its violations (VF-073). */
        public boolean lapsed(LocalDate today, int graceDays) {
            return !paid && today.isAfter(dueDate.plusDays(graceDays));
        }
    }

    /** Plan rules (configuration; VF-071 BRD defaults: minimum total 5,000 local currency, 3 to 12 months). */
    public record Terms(long minimumTotalMinor, int minMonths, int maxMonths, int graceDays) {

        public Terms {
            if (minimumTotalMinor < 0 || minMonths < 1 || maxMonths < minMonths || maxMonths > 60 || graceDays < 0
                    || graceDays > 90) {
                throw new IllegalArgumentException(
                        "instalment terms: minimum total >= 0, 1 <= min months <= max months <= 60, grace 0..90 days");
            }
        }
    }

    public InstalmentPlan {
        Objects.requireNonNull(id);
        Objects.requireNonNull(number);
        Objects.requireNonNull(status);
        violationNumbers = List.copyOf(violationNumbers);
        instalments = List.copyOf(instalments);
    }

    /**
     * Splits {@code total} into {@code months} monthly instalments, the first due one month after {@code start}; the
     * remainder of the division goes to the last instalment, so the sum is exactly the total.
     */
    public static List<Instalment> schedule(long total, int months, LocalDate start) {
        if (months < 1 || total < months) {
            throw DomainException.validation("INSTALMENT_PLAN_INVALID", "the total cannot be split into the months",
                    List.of("VF-071"));
        }
        long base = total / months;
        List<Instalment> out = new ArrayList<>(months);
        for (int i = 1; i <= months; i++) {
            long amount = i == months ? total - base * (months - 1) : base;
            out.add(new Instalment(i, start.plusMonths(i), amount, null, false, null));
        }
        return out;
    }

    /** Checks the request against the terms (VF-071). */
    public static void check(Terms terms, long total, int months) {
        if (total < terms.minimumTotalMinor()) {
            throw DomainException.validation("INSTALMENT_TOTAL_TOO_LOW",
                    "the violations total less than the minimum for an instalment plan", List.of("VF-071"),
                    String.valueOf(terms.minimumTotalMinor()));
        }
        if (months < terms.minMonths() || months > terms.maxMonths()) {
            throw DomainException.validation("INSTALMENT_MONTHS_INVALID", "the number of instalments is out of range",
                    List.of("VF-071"), terms.minMonths() + ".." + terms.maxMonths());
        }
    }

    /** The item reference of an instalment's payable: {@code <plan number>-<two-digit sequence>}. */
    public static String itemRef(String planNumber, int seq) {
        return planNumber + "-" + String.format("%02d", seq);
    }

    public boolean anyLapsed(LocalDate today) {
        return instalments.stream().anyMatch(i -> i.lapsed(today, graceDays));
    }

    public boolean allPaid() {
        return instalments.stream().allMatch(Instalment::paid);
    }
}

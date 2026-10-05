package gov.itms.hold.domain;

import gov.itms.hold.domain.EligibilityPolicy.Block;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The eligibility of subjects for one service on one day (CC-032): blocked when a hold in force on any of them blocks
 * the service (CC-030). Rule-based eligibility (rules-service, CC-050..CC-053) is added here when that service exists.
 *
 * @param serviceCode the service asked about
 * @param checkedOn   the day the holds were evaluated (service clock, Q-053)
 * @param blocks      what the customer is told, without repeats, in the order the holds were found
 * @param blockingHolds the holds that block, for staff and audit only; never shown to the customer
 * @param overriddenHolds holds that would block but are overridden for the transaction checked (CC-034), for staff and
 *                        audit only
 */
public record Eligibility(String serviceCode, LocalDate checkedOn, List<Block> blocks, List<UUID> blockingHolds,
                          List<UUID> overriddenHolds) {

    /** A subject may be named once per check; a check covers at most this many (the transaction and its parties). */
    public static final int MAX_SUBJECTS = 5;
    private static final Pattern SERVICE = Pattern.compile(Codes.SERVICE_CODE);

    public Eligibility {
        blocks = List.copyOf(blocks);
        blockingHolds = List.copyOf(blockingHolds);
        overriddenHolds = List.copyOf(overriddenHolds);
    }

    public boolean eligible() {
        return blocks.isEmpty();
    }

    /** Validates what is asked: a service code of the catalogue (CC-020) and 1-5 distinct subjects. */
    public static List<Subject> request(String serviceCode, List<Subject> subjects) {
        if (serviceCode == null || !SERVICE.matcher(serviceCode).matches()) {
            throw new DomainException(DomainException.Kind.VALIDATION, "INVALID_SERVICE_CODE",
                    "a service code must be 2-20 characters of A-Z, 0-9 or _, starting with a letter", List.of("CC-032"));
        }
        if (subjects == null || subjects.isEmpty() || subjects.size() > MAX_SUBJECTS) {
            throw new DomainException(DomainException.Kind.VALIDATION, "LIST_SIZE_INVALID",
                    "subjects needs 1 to " + MAX_SUBJECTS + " entries", List.of("CC-032"), "subjects", 1, MAX_SUBJECTS);
        }
        if (subjects.stream().anyMatch(Objects::isNull) || Set.copyOf(subjects).size() != subjects.size()) {
            throw new DomainException(DomainException.Kind.VALIDATION, "LIST_DUPLICATES",
                    "subjects must not repeat an entry", List.of("CC-032"), "subjects");
        }
        return List.copyOf(subjects);
    }

    /** Evaluates {@code holds} (all holds on the subjects) for {@code serviceCode} on {@code today}. */
    public static Eligibility evaluate(String serviceCode, List<Hold> holds, EligibilityPolicy policy,
                                       LocalDate today) {
        return evaluate(serviceCode, holds, Set.of(), policy, today);
    }

    /**
     * Evaluates {@code holds} for {@code serviceCode} on {@code today}, ignoring the holds in {@code overridden}: those
     * a supervisor overrode for the transaction being checked (CC-034). An overridden hold that would not block is not
     * listed as overridden.
     */
    public static Eligibility evaluate(String serviceCode, List<Hold> holds, Set<UUID> overridden,
                                       EligibilityPolicy policy, LocalDate today) {
        Set<Block> blocks = new LinkedHashSet<>();
        List<UUID> blocking = new ArrayList<>();
        List<UUID> skipped = new ArrayList<>();
        for (Hold hold : holds) {
            if (!hold.blocks(serviceCode, today)) {
                continue;
            }
            if (overridden.contains(hold.id())) {
                skipped.add(hold.id());
            } else {
                blocks.add(policy.blockFor(hold.spec().holdType()));
                blocking.add(hold.id());
            }
        }
        return new Eligibility(serviceCode, today, List.copyOf(blocks), blocking, skipped);
    }
}

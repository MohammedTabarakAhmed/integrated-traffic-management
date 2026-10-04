package gov.itms.transaction.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An availability rule of a service (CC-026). Rules are never edited or deleted; a rule can only be ended earlier, by
 * an approved change, and not in the past.
 */
public final class AvailabilityRule {

    private final UUID id;
    private final String serviceCode;
    private AvailabilityRuleSpec spec;
    private final UUID changeId;
    private UUID endedByChange;

    public AvailabilityRule(UUID id, String serviceCode, AvailabilityRuleSpec spec, UUID changeId, UUID endedByChange) {
        this.id = Objects.requireNonNull(id);
        this.serviceCode = Codes.serviceCode(serviceCode);
        this.spec = Objects.requireNonNull(spec);
        this.changeId = Objects.requireNonNull(changeId);
        this.endedByChange = endedByChange;
    }

    public static AvailabilityRule add(String serviceCode, AvailabilityRuleSpec spec, UUID changeId) {
        return new AvailabilityRule(UUID.randomUUID(), serviceCode, spec, changeId, null);
    }

    /**
     * Ends the rule on {@code endDate} (its last day in force). The end may only move earlier, not before the start,
     * and not before {@code today}: availability on past days is never rewritten.
     */
    public void end(LocalDate endDate, UUID change, LocalDate today) {
        checkEnd(endDate, today);
        this.spec = spec.endingOn(endDate);
        this.endedByChange = Objects.requireNonNull(change);
    }

    /** The checks of {@link #end}, without ending (used when a change is submitted). */
    public void checkEnd(LocalDate endDate, LocalDate today) {
        if (endDate == null || endDate.isBefore(spec.effectiveFrom())) {
            throw DomainException.validation("RULE_END_INVALID", "a rule cannot end before it starts");
        }
        if (endDate.isBefore(today)) {
            throw DomainException.validation("RULE_END_IN_PAST", "a rule cannot be ended on a day that has passed");
        }
        if (spec.effectiveTo() != null && !endDate.isBefore(spec.effectiveTo())) {
            throw DomainException.validation("RULE_END_NOT_EARLIER", "a rule can only be ended earlier than planned");
        }
    }

    public UUID id() { return id; }
    public String serviceCode() { return serviceCode; }
    public AvailabilityRuleSpec spec() { return spec; }
    public UUID changeId() { return changeId; }
    public UUID endedByChange() { return endedByChange; }
}

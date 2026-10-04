package gov.itms.transaction.domain;

import java.time.LocalDate;

/**
 * Enables or disables a service for a region ({@code *} = every region) and a channel ({@code null} = every channel)
 * from {@code effectiveFrom} to {@code effectiveTo} inclusive ({@code null} = open-ended) (CC-026).
 */
public record AvailabilityRuleSpec(String region, Channel channel, boolean enabled, LocalDate effectiveFrom,
                                   LocalDate effectiveTo) {

    public AvailabilityRuleSpec {
        region = Codes.regionOrAll("region", region);
        if (effectiveFrom == null) {
            throw DomainException.validation("RULE_START_REQUIRED", "an availability rule needs a start date");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw DomainException.validation("RULE_DATES_INVALID", "the end date cannot be before the start date");
        }
    }

    public boolean allRegions() {
        return Codes.ALL.equals(region);
    }

    public boolean allChannels() {
        return channel == null;
    }

    /** 3 = region and channel, 2 = region only, 1 = channel only, 0 = every region and channel. */
    public int specificity() {
        return (allRegions() ? 0 : 2) + (allChannels() ? 0 : 1);
    }

    public boolean covers(String region, Channel channel) {
        return (allRegions() || this.region.equals(region)) && (allChannels() || this.channel == channel);
    }

    public boolean inForceOn(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    /** Same region and channel, and the date ranges share at least one day. */
    public boolean overlaps(AvailabilityRuleSpec other) {
        return region.equals(other.region) && channel == other.channel
                && (effectiveTo == null || !other.effectiveFrom.isAfter(effectiveTo))
                && (other.effectiveTo == null || !effectiveFrom.isAfter(other.effectiveTo));
    }

    public AvailabilityRuleSpec endingOn(LocalDate endDate) {
        return new AvailabilityRuleSpec(region, channel, enabled, effectiveFrom, endDate);
    }
}

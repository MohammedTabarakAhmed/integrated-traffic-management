package gov.itms.transaction.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;

/**
 * Whether a service is available in a region on a channel on a date, and why (CC-026, CC-112; ADR-028):
 * <ol>
 *   <li>the definition must offer the region and the channel;</li>
 *   <li>then the rule in force with the most specific match decides (region and channel, region only, channel only,
 *       every region and channel); rules with the same region and channel never overlap, so at most one rule per
 *       level applies;</li>
 *   <li>with no rule in force, the service is available.</li>
 * </ol>
 */
public record Availability(String serviceCode, String region, Channel channel, LocalDate date, boolean available,
                           Reason reason, UUID ruleId) {

    public enum Reason { OFFERED, NOT_OFFERED_IN_REGION, NOT_OFFERED_ON_CHANNEL, ENABLED_BY_RULE, DISABLED_BY_RULE }

    public static Availability of(ServiceSpec service, Collection<AvailabilityRule> rules, String region,
                                  Channel channel, LocalDate date) {
        Codes.code("region", region);
        if (channel == null || date == null) {
            throw DomainException.validation("AVAILABILITY_QUERY_INVALID", "a region, a channel and a date are needed");
        }
        String code = service.code();
        if (!service.offersRegion(region)) {
            return new Availability(code, region, channel, date, false, Reason.NOT_OFFERED_IN_REGION, null);
        }
        if (!service.offersChannel(channel)) {
            return new Availability(code, region, channel, date, false, Reason.NOT_OFFERED_ON_CHANNEL, null);
        }
        return rules.stream()
                .filter(r -> r.serviceCode().equals(code))
                .filter(r -> r.spec().covers(region, channel) && r.spec().inForceOn(date))
                .max(Comparator.comparingInt(r -> r.spec().specificity()))
                .map(r -> new Availability(code, region, channel, date, r.spec().enabled(),
                        r.spec().enabled() ? Reason.ENABLED_BY_RULE : Reason.DISABLED_BY_RULE, r.id()))
                .orElseGet(() -> new Availability(code, region, channel, date, true, Reason.OFFERED, null));
    }
}

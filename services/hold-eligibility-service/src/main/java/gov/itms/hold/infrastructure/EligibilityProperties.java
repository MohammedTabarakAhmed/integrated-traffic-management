package gov.itms.hold.infrastructure;

import gov.itms.hold.domain.EligibilityPolicy;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;

/**
 * Which hold types the customer is told about when they block a service, and the next step for each (CC-032; Q-053,
 * owner decision 2026-10-05: masked per type). Business configuration, set per environment, never in the code; any
 * hold type not listed is masked.
 *
 * @param disclosedHoldTypes the disclosed hold types, each with its next-step code
 */
@ConfigurationProperties("itms.eligibility")
public record EligibilityProperties(List<Disclosed> disclosedHoldTypes) {

    public record Disclosed(String holdType, String nextStep) {
    }

    /**
     * The policy, refusing start-up when a hold type is listed twice, a code is malformed, or a reason or next step
     * has no customer text (so a customer is never shown a bare code).
     */
    public EligibilityPolicy toPolicy(MessageSource messages) {
        Map<String, String> map = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Disclosed d : disclosedHoldTypes == null ? List.<Disclosed>of() : disclosedHoldTypes) {
            if (d == null || d.holdType() == null || d.nextStep() == null || !seen.add(d.holdType())) {
                throw new IllegalStateException("itms.eligibility.disclosed-hold-types lists a hold type twice or "
                        + "without its code or next step");
            }
            map.put(d.holdType(), d.nextStep());
        }
        EligibilityPolicy policy;
        try {
            policy = new EligibilityPolicy(map);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("itms.eligibility.disclosed-hold-types: " + e.getMessage(), e);
        }
        requireText(messages, EligibilityPolicy.REASON_KEY + EligibilityPolicy.RESTRICTED);
        requireText(messages, EligibilityPolicy.NEXT_STEP_KEY + EligibilityPolicy.CONTACT_AUTHORITY);
        policy.disclosedHoldTypes().forEach((type, step) -> {
            requireText(messages, EligibilityPolicy.REASON_KEY + type);
            requireText(messages, EligibilityPolicy.NEXT_STEP_KEY + step);
        });
        return policy;
    }

    private static void requireText(MessageSource messages, String key) {
        try {
            messages.getMessage(key, null, Locale.ENGLISH);
        } catch (NoSuchMessageException e) {
            throw new IllegalStateException("no customer text for " + key + " in messages.properties", e);
        }
    }
}

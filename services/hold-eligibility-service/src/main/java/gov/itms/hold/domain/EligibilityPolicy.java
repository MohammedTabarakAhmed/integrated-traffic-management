package gov.itms.hold.domain;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a customer is told when a hold blocks a service (CC-032; owner decision 2026-10-05, Q-053): each hold type is
 * either disclosed, with its own next step, or masked. A masked hold (a police circular, a court order, any type not
 * configured) is shown only as {@value #RESTRICTED} with the next step {@value #CONTACT_AUTHORITY}, so the customer
 * learns nothing a sensitive hold should hide. Staff with {@code hold.read} still see the hold itself.
 *
 * @param disclosedHoldTypes hold type code → next-step code, for the types the customer may be told about
 */
public record EligibilityPolicy(Map<String, String> disclosedHoldTypes) {

    /** Reason code shown for a masked hold. */
    public static final String RESTRICTED = "RESTRICTED";
    /** Next step shown for a masked hold. */
    public static final String CONTACT_AUTHORITY = "CONTACT_AUTHORITY";
    /** Message keys of the customer texts: {@code eligibility.reason.<code>}, {@code eligibility.next-step.<code>}. */
    public static final String REASON_KEY = "eligibility.reason.";
    public static final String NEXT_STEP_KEY = "eligibility.next-step.";
    private static final Pattern CODE = Pattern.compile(Codes.CODE);

    public EligibilityPolicy {
        disclosedHoldTypes = Map.copyOf(disclosedHoldTypes == null ? Map.of() : disclosedHoldTypes);
        disclosedHoldTypes.forEach((type, step) -> {
            if (!CODE.matcher(type).matches() || RESTRICTED.equals(type)) {
                throw new IllegalArgumentException("disclosed hold type '" + type + "' is not a valid hold type code");
            }
            if (step == null || !CODE.matcher(step).matches()) {
                throw new IllegalArgumentException("next step of hold type " + type + " is not a valid code");
            }
        });
    }

    /** One reason a service is blocked, as the customer sees it. */
    public record Block(String reasonCode, String nextStep) {
    }

    /** How a hold of {@code holdType} is shown to the customer. */
    public Block blockFor(String holdType) {
        String step = disclosedHoldTypes.get(holdType);
        return step == null ? new Block(RESTRICTED, CONTACT_AUTHORITY) : new Block(holdType, step);
    }
}

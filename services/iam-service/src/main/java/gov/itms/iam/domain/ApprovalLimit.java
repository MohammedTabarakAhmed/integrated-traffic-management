package gov.itms.iam.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Highest amount a role may approve for one kind of approval in one currency (CC-003), in minor units. Limit types
 * and amounts are configuration administered at runtime; none is hard-coded.
 */
public record ApprovalLimit(String limitType, long maxAmountMinor, String currency) {

    public static final String LIMIT_TYPE = "^[A-Z0-9_]{1,40}$";
    public static final String CURRENCY = "^[A-Z]{3}$";
    public static final int MAX_PER_ROLE = 100;
    private static final Pattern TYPE = Pattern.compile(LIMIT_TYPE);
    private static final Pattern CURRENCY_CODE = Pattern.compile(CURRENCY);

    public ApprovalLimit {
        if (limitType == null || !TYPE.matcher(limitType).matches()) {
            throw DomainException.validation("LIMIT_TYPE_INVALID",
                    "the limit type must be 1-40 characters of A-Z, 0-9 or _", String.valueOf(limitType));
        }
        if (currency == null || !CURRENCY_CODE.matcher(currency).matches()) {
            throw DomainException.validation("CURRENCY_INVALID", "the currency must be an ISO 4217 code",
                    String.valueOf(currency));
        }
        if (maxAmountMinor < 0) {
            throw DomainException.validation("LIMIT_AMOUNT_NEGATIVE", "an approval limit cannot be negative");
        }
    }

    /**
     * Validates a role's complete set of limits: at most {@value #MAX_PER_ROLE}, each (type, currency) once.
     * Returns them in a stable order (type, then currency).
     */
    public static List<ApprovalLimit> validSet(Collection<ApprovalLimit> limits) {
        if (limits == null) {
            throw DomainException.validation("LIMITS_REQUIRED", "the list of limits is required (it may be empty)");
        }
        if (limits.size() > MAX_PER_ROLE) {
            throw DomainException.validation("TOO_MANY_LIMITS", "a role can have at most 100 approval limits");
        }
        Set<String> keys = new HashSet<>();
        for (ApprovalLimit l : limits) {
            if (!keys.add(l.limitType() + "/" + l.currency())) {
                throw DomainException.validation("LIMIT_DUPLICATE",
                        "the limit " + l.limitType() + " in " + l.currency() + " is listed more than once",
                        l.limitType(), l.currency());
            }
        }
        List<ApprovalLimit> sorted = new ArrayList<>(limits);
        sorted.sort(Comparator.comparing(ApprovalLimit::limitType).thenComparing(ApprovalLimit::currency));
        return List.copyOf(sorted);
    }
}

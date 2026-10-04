package gov.itms.refdata.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The maintainable attributes of a reference item (CC-110): names in both languages, active flag, effective dates,
 * parent code and partner codes.
 */
public record ItemData(BilingualName name, boolean active, LocalDate effectiveFrom, LocalDate effectiveTo,
                       String parentCode, Map<String, String> externalCodes, int sortOrder) {

    private static final Pattern EXTERNAL_VALUE = Pattern.compile("^[\\p{L}\\p{N} ._/-]{1,64}$");

    public ItemData {
        if (name == null) {
            throw DomainException.validation("NAME_REQUIRED", "name is required");
        }
        if (effectiveFrom == null) {
            throw DomainException.validation("EFFECTIVE_FROM_REQUIRED", "effectiveFrom is required");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw DomainException.validation("EFFECTIVE_DATES_INVALID", "effectiveTo must not be before effectiveFrom");
        }
        Codes.optional("parentCode", parentCode);
        if (sortOrder < 0) {
            throw DomainException.validation("SORT_ORDER_INVALID", "sortOrder must not be negative");
        }
        TreeMap<String, String> codes = new TreeMap<>();
        if (externalCodes != null) {
            externalCodes.forEach((partner, value) -> {
                Codes.require("externalCodes partner", partner);
                if (value == null || !EXTERNAL_VALUE.matcher(value).matches()) {
                    throw DomainException.validation("EXTERNAL_CODE_INVALID",
                            "external code for " + partner + " must be 1-64 letters, digits, space or . _ / -", partner);
                }
                codes.put(partner, value);
            });
        }
        externalCodes = java.util.Collections.unmodifiableMap(codes);
    }

    /** True when the item is in force on {@code date} (effective dates are inclusive). */
    public boolean isEffectiveOn(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }
}

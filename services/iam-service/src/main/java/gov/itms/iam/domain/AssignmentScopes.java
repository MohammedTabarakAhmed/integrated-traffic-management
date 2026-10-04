package gov.itms.iam.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Data scopes of a role assignment (CC-001): regions, service centres and modules. Each dimension needs at least
 * one value; {@code *} means all values and must then be the only one. The BRD scopes every role to "one or more
 * regions and service centres" (§3.3), so no dimension may be left empty.
 */
public record AssignmentScopes(SortedSet<String> regions, SortedSet<String> centres, SortedSet<String> modules) {

    public static final String ALL = "*";
    public static final int MAX_VALUES = 100;
    private static final Pattern VALUE = Pattern.compile(Codes.SCOPE_VALUE);

    public AssignmentScopes {
        regions = clean("regions", regions);
        centres = clean("centres", centres);
        modules = clean("modules", modules);
    }

    public static AssignmentScopes of(Collection<String> regions, Collection<String> centres,
                                      Collection<String> modules) {
        return new AssignmentScopes(copy(regions), copy(centres), copy(modules));
    }

    public static AssignmentScopes national() {
        return of(java.util.List.of(ALL), java.util.List.of(ALL), java.util.List.of(ALL));
    }

    private static SortedSet<String> copy(Collection<String> values) {
        return values == null ? null : new TreeSet<>(values);
    }

    private static SortedSet<String> clean(String dimension, Collection<String> values) {
        if (values == null || values.isEmpty()) {
            throw DomainException.validation("SCOPE_REQUIRED", dimension + " needs at least one value (or *)",
                    dimension);
        }
        if (values.size() > MAX_VALUES) {
            throw DomainException.validation("SCOPE_TOO_LARGE", dimension + " can have at most 100 values", dimension);
        }
        for (String v : values) {
            if (v == null || !VALUE.matcher(v).matches()) {
                throw DomainException.validation("SCOPE_VALUE_INVALID", dimension + " contains an invalid value",
                        dimension);
            }
        }
        if (values.contains(ALL) && values.size() > 1) {
            throw DomainException.validation("SCOPE_ALL_EXCLUSIVE", dimension + ": * must be the only value",
                    dimension);
        }
        return Collections.unmodifiableSortedSet(new TreeSet<>(values));
    }
}

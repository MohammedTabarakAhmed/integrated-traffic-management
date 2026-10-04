package gov.itms.platform.security;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Data scopes of one grant (CC-001): regions, service centres and modules. {@link #ALL} ({@code *}) in a dimension
 * means every value of that dimension and is then the only value.
 */
public record Scopes(Set<String> regions, Set<String> centres, Set<String> modules) {

    public static final String ALL = "*";
    public static final Scopes NATIONAL = new Scopes(Set.of(ALL), Set.of(ALL), Set.of(ALL));

    public Scopes {
        regions = Set.copyOf(new TreeSet<>(regions));
        centres = Set.copyOf(new TreeSet<>(centres));
        modules = Set.copyOf(new TreeSet<>(modules));
    }

    /**
     * True if this grant reaches the target. A {@code null} target dimension means the resource is not scoped by
     * that dimension and always matches.
     */
    public boolean allows(ScopeTarget target) {
        return matches(regions, target.region()) && matches(centres, target.centre())
                && matches(modules, target.module());
    }

    /**
     * True if every value requested in {@code other} lies within this grant. Requesting {@code *} is only covered
     * by {@code *}. Used to stop administrators granting beyond their own scope.
     */
    public boolean covers(Scopes other) {
        return coversDimension(regions, other.regions()) && coversDimension(centres, other.centres())
                && coversDimension(modules, other.modules());
    }

    private static boolean matches(Set<String> granted, String value) {
        return value == null || granted.contains(ALL) || granted.contains(value);
    }

    private static boolean coversDimension(Set<String> granted, Collection<String> requested) {
        return granted.contains(ALL) || (!requested.contains(ALL) && granted.containsAll(requested));
    }
}
